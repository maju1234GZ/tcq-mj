#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
提词器 · 云端ASR参考服务端
协议见 docs/插件接口契约.md 第四节：
  客户端 -> config(JSON) / PCM二进制帧(16bit 16kHz 单声道) / end(JSON)
  服务端 -> {"type":"partial","text":"累计全文"} / {"type":"final","text":...} / {"type":"error",...}

依赖：funasr + torch(CPU即可) + websockets
模型：paraformer-zh-streaming（中文流式，CPU 可跑）
"""
import asyncio
import json
import logging

import numpy as np
import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("asr")

HOST, PORT = "0.0.0.0", 8765
CHUNK_SAMPLES = 9600          # 600ms @16kHz（funasr 流式 chunk [0,10,5]）
CHUNK_BYTES = CHUNK_SAMPLES * 2

_model = None

def get_model():
    global _model
    if _model is None:
        from funasr import AutoModel
        log.info("加载 paraformer-zh-streaming 模型（首次会下载约 1GB）...")
        _model = AutoModel(model="paraformer-zh-streaming", model_revision="v2.0.4", disable_update=True)
        log.info("模型加载完成")
    return _model


class Session:
    def __init__(self):
        self.pcm_buf = bytearray()
        self.cache = {}
        self.text = ""          # 累计全文
        self.closed = False

    def reset(self):
        self.pcm_buf.clear()
        self.cache = {}
        self.text = ""


def run_asr(sess: Session, chunk: np.ndarray, is_final: bool) -> str:
    """同步推理（放到线程池里跑），返回本段识别文本"""
    model = get_model()
    res = model.generate(
        input=chunk,
        cache=sess.cache,
        is_final=is_final,
        chunk_size=[0, 10, 5],
        encoder_chunk_look_back=4,
        decoder_chunk_look_back=1,
    )
    if res and isinstance(res, list) and res[0].get("text"):
        return res[0]["text"]
    return ""


async def handle(ws):
    sess = Session()
    peer = ws.remote_address
    log.info("客户端接入 %s", peer)
    loop = asyncio.get_running_loop()
    try:
        async for msg in ws:
            if isinstance(msg, str):
                try:
                    data = json.loads(msg)
                except json.JSONDecodeError:
                    continue
                t = data.get("type")
                if t == "config":
                    log.info("%s config: %s", peer, data)
                elif t == "end":
                    # 冲刷剩余缓存，产出 final，然后重置等下一段
                    if sess.pcm_buf:
                        chunk = np.frombuffer(bytes(sess.pcm_buf), dtype=np.int16).astype(np.float32) / 32768.0
                        sess.pcm_buf.clear()
                        sess.text += await loop.run_in_executor(None, run_asr, sess, chunk, True)
                    else:
                        await loop.run_in_executor(None, run_asr, sess, np.zeros(1, dtype=np.float32), True)
                    await ws.send(json.dumps({"type": "final", "text": sess.text}, ensure_ascii=False))
                    sess.reset()
            else:
                # 二进制 PCM 帧
                sess.pcm_buf.extend(msg)
                while len(sess.pcm_buf) >= CHUNK_BYTES:
                    raw = bytes(sess.pcm_buf[:CHUNK_BYTES])
                    del sess.pcm_buf[:CHUNK_BYTES]
                    chunk = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
                    seg = await loop.run_in_executor(None, run_asr, sess, chunk, False)
                    if seg:
                        sess.text += seg
                        await ws.send(json.dumps({"type": "partial", "text": sess.text}, ensure_ascii=False))
    except websockets.ConnectionClosed:
        pass
    except Exception as e:  # noqa: BLE001
        log.exception("会话异常")
        try:
            await ws.send(json.dumps({"type": "error", "message": str(e)}, ensure_ascii=False))
        except Exception:
            pass
    finally:
        log.info("客户端断开 %s", peer)


async def main():
    get_model()  # 启动即加载模型，避免首连接等待
    async with websockets.serve(handle, HOST, PORT, max_size=None, ping_interval=20, ping_timeout=30):
        log.info("ASR WebSocket 服务已启动: ws://%s:%d/ws", HOST, PORT)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
