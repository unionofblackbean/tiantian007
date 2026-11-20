"""Standard-library SuperWY API client. Does not require Minecraft on the caller."""
import argparse
import json
import os
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description="Submit an ordered item list to SuperWY")
    parser.add_argument("--url", default="http://127.0.0.1:8765")
    parser.add_argument("--token-env", default="SUPERWY_TOKEN")
    parser.add_argument("--fake-player", required=True)
    parser.add_argument("--items", required=True, help="JSON file containing an array of item requests")
    parser.add_argument("--key", required=True, help="Persist and reuse this key for retries of this request")
    parser.add_argument("--wait", action="store_true")
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    token = os.environ.get(args.token_env)
    if not token:
        parser.error(f"Set the {args.token_env} environment variable to your API credential")
    with open(args.items, encoding="utf-8") as handle:
        items = json.load(handle)
    body = {"warehouseId": "main", "items": items,
            "delivery": {"method": "carpet_fake_player", "fakePlayerName": args.fake_player}}

    def call(path, payload=None):
        headers = {"Authorization": "Bearer " + token}
        if payload is not None:
            headers.update({"Content-Type": "application/json", "Idempotency-Key": args.key})
        request = urllib.request.Request(args.url.rstrip("/") + "/api/v1/" + path,
                                         data=None if payload is None else json.dumps(payload).encode(),
                                         headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise SystemExit(f"HTTP {error.code}: {error.read().decode('utf-8')}") from None
        except urllib.error.URLError as error:
            raise SystemExit(f"Network failure: {error.reason}. Retry with the SAME --key {args.key}.") from None

    order = call("orders", body)
    print("Order:", order["orderId"], "recipient:", order["delivery"]["fakePlayerName"], "key:", args.key)
    deadline, last = time.monotonic() + args.timeout, None
    while True:
        marker = (order["state"], order.get("blockingReason"))
        if marker != last:
            print(json.dumps({"state": order["state"], "reason": order.get("blockingReason"), "lines": order["lines"]}, ensure_ascii=False))
            last = marker
        if not args.wait or order["state"] in {"stored_offline", "partial_stored_offline", "cancelled", "failed", "delivery_uncertain", "recovering"}:
            break
        if time.monotonic() > deadline:
            raise SystemExit("Polling timed out; the order remains stored. Query the existing order; do not create a replacement.")
        time.sleep(2)
        order = call("orders/" + order["orderId"])


if __name__ == "__main__":
    main()
