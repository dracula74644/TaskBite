#!/usr/bin/env python3
"""Create a persistent local release identity once. Never replace an existing key."""
from pathlib import Path
import os
import secrets
import subprocess

root = Path(__file__).resolve().parents[1]
keystore = root / 'taskbite-release.jks'
properties = root / 'signing.properties'
if keystore.exists() or properties.exists():
    raise SystemExit('Signing material already exists; keep using it for every update.')
password = secrets.token_urlsafe(32)
env = os.environ.copy()
env['TASKBITE_STORE_PASS'] = password
subprocess.run([
    'keytool', '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12',
    '-alias', 'taskbite', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
    '-storepass:env', 'TASKBITE_STORE_PASS', '-keypass:env', 'TASKBITE_STORE_PASS',
    '-dname', 'CN=Zeeshan Mirza, OU=Dracula, O=TaskBite', '-noprompt',
], env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
os.chmod(keystore, 0o600)
fd = os.open(properties, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
with os.fdopen(fd, 'w') as stream:
    stream.write(f'storeFile={keystore.name}\nstorePassword={password}\nkeyAlias=taskbite\nkeyPassword={password}\n')
print('Release key created. Back up taskbite-release.jks and signing.properties privately.')
