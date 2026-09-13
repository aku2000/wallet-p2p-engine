#!/usr/bin/env python3
import sys
import ssl
import pg8000.native

if len(sys.argv) < 3:
    print("Usage: python3 seed_wallet.py <user_id> <amount_paise>")
    sys.exit(1)

user_id = sys.argv[1]
amount = int(sys.argv[2])

ctx = ssl.create_default_context()
con = pg8000.native.Connection(
    user='wallet',
    password='PNTWOqxHD1rS7TJTAj9je01hOUOZzMT0',
    host='dpg-daj894gjo6nc73crjfm0-a.singapore-postgres.render.com',
    port=5432,
    database='wallet_re17',
    ssl_context=ctx
)

res = con.run("UPDATE wallets SET balance = balance + :amt WHERE user_id = :uid RETURNING id, balance", amt=amount, uid=user_id)
if res:
    print(f"Seeded wallet for {user_id}: ID={res[0][0]}, New Balance={res[0][1]} paise")
else:
    print(f"Wallet for {user_id} not found in database.")
con.close()

