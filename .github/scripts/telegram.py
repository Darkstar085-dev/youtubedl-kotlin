#!/usr/bin/env python3

import asyncio
import os
import sys
from pathlib import Path

from telethon import TelegramClient


UPLOAD_TIMEOUT = 600
MAX_ATTEMPTS = 2


async def send_debug_apk(path: Path) -> None:
    api_id = os.environ.get("TELEGRAM_API_ID")
    api_hash = os.environ.get("TELEGRAM_API_HASH")
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat_id = os.environ.get("TELEGRAM_CHAT_ID")

    for name, value in (
        ("TELEGRAM_API_ID", api_id),
        ("TELEGRAM_API_HASH", api_hash),
        ("TELEGRAM_BOT_TOKEN", token),
        ("TELEGRAM_CHAT_ID", chat_id),
    ):
        if not value:
            raise SystemExit(f"{name} is missing.")

    if not path.is_file() or path.stat().st_size == 0:
        raise SystemExit(f"APK is missing or empty: {path}")

    try:
        api_id_value = int(api_id)
        chat_id_value = int(chat_id)
    except ValueError as exc:
        raise SystemExit("TELEGRAM_API_ID and TELEGRAM_CHAT_ID must be integers.") from exc

    for attempt in range(1, MAX_ATTEMPTS + 1):
        client = TelegramClient(
            f"youtubedl-kotlin-demo-ci-{attempt}",
            api_id_value,
            api_hash,
            timeout=30,
            connection_retries=3,
            request_retries=3,
            retry_delay=5,
        )

        try:
            await client.start(bot_token=token)
            await asyncio.wait_for(
                client.send_file(
                    chat_id_value,
                    path,
                    force_document=True,
                ),
                timeout=UPLOAD_TIMEOUT,
            )
            print(f"Telegram upload completed: {path.name}", flush=True)
            return
        except asyncio.TimeoutError:
            print(
                f"Telegram upload attempt {attempt} timed out.",
                flush=True,
            )
        except Exception as exc:
            print(
                f"Telegram upload attempt {attempt} failed: "
                f"{type(exc).__name__}: {exc}",
                flush=True,
            )
        finally:
            await client.disconnect()

    raise RuntimeError(
        f"Telegram upload failed after {MAX_ATTEMPTS} attempts."
    )


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: telegram.py <apk-path>")

    asyncio.run(send_debug_apk(Path(sys.argv[1])))


if __name__ == "__main__":
    main()
