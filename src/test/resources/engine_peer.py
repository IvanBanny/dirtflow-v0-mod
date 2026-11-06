"""Real engine HTTP/queue/codec with deterministic sampling for mod tests."""

import pathlib
import socket
import sys
import time

import numpy as np
import uvicorn
from dirtflow_engine import jobs, server, voxel


def generate(job: jobs.Job, mailbox: jobs.Mailbox) -> dict[str, object]:
    request = job.request
    assert request.prompt in {"interop", "wait for cancellation"}
    assert request.options.batch_size == request.options.candidates == 1
    assert request.options.stage1_steps == request.options.stage2_steps == 30
    assert request.options.cfg_scale == 5
    assert np.all(job.tensor.array() == 0)
    if request.prompt == "wait for cancellation":
        while not job.canceled.wait(0.01):
            pass
        return {}
    values = np.indices(request.region.size).sum(axis=0) % 2
    states = [voxel.AIR, "minecraft:stone"]
    preview = voxel.from_array(values, tuple(request.region.min), states)
    mailbox.put(
        "preview",
        provisional=True,
        full_replacement=True,
        frame_sequence=1,
        candidate_index=0,
        candidate_count=1,
        seed=123,
        stage="blockstates",
        completed_step=3,
        total_steps=30,
        stage_end=False,
        dimension=request.dimension,
        origin=request.region.min,
        size=request.region.size,
        snapshot=voxel.pack(preview, "zlib"),
    )
    mailbox.put("progress", phase="GENERATING", fraction=0.5, message="material step")
    time.sleep(0.3)
    return {
        "diff": voxel.pack(
            voxel.from_array(
                values, tuple(request.region.min), states, np.zeros_like(values)
            ),
            "zlib",
        )
    }


if len(sys.argv) > 1:
    root = pathlib.Path(sys.argv[1])
    values = np.indices((58, 58, 58)).sum(axis=0) % 2
    tensor = voxel.from_array(values, (-7, 11, 25), [voxel.AIR, "minecraft:stone"])
    (root / "python.vxt").write_bytes(voxel.encode(tensor))
    raise SystemExit

sock = socket.socket()
sock.bind(("127.0.0.1", 0))
sock.listen(16)
print(sock.getsockname()[1], flush=True)
app = server.create_app(generate, token="interop-token")
uvicorn.Server(uvicorn.Config(app, log_level="warning")).run(sockets=[sock])
