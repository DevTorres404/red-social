import { generateKeyPairSync } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const envPath = fileURLToPath(new URL('../.env', import.meta.url));
let env;
try { env = readFileSync(envPath, 'utf8'); }
catch { throw new Error('Create .env from .env.example before generating VAPID keys.'); }

const publicLine = /^VAPID_PUBLIC_KEY=(.*)$/m.exec(env);
const privateLine = /^VAPID_PRIVATE_KEY=(.*)$/m.exec(env);
if (!publicLine || !privateLine) throw new Error('.env is missing the VAPID key settings.');
if (publicLine[1].trim() || privateLine[1].trim())
  throw new Error('VAPID keys are already configured; refusing to rotate existing subscriptions.');

const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const jwk = privateKey.export({ format: 'jwk' });
const rawPublic = Buffer.concat([Buffer.from([4]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')]);
env = env.replace(/^VAPID_PUBLIC_KEY=.*$/m, `VAPID_PUBLIC_KEY=${rawPublic.toString('base64url')}`)
  .replace(/^VAPID_PRIVATE_KEY=.*$/m, `VAPID_PRIVATE_KEY=${jwk.d}`);
writeFileSync(envPath, env);
console.log('VAPID keys saved to ignored .env. Keep the private key secret; restarting backend is required.');
