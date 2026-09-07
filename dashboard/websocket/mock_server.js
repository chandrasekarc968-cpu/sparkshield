/**
 * SparkShield Mock WebSocket Server Launcher (Node.js adapter)
 *
 * Launches python_core.mock_ws_server with standard port 8765.
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const rootDir = path.resolve(__dirname, '..', '..');

const port = process.env.WS_PORT || '8765';
console.log(`[SparkShield] Starting Python Mock WebSocket Server on port ${port}...`);

const pyProcess = spawn('python', ['-m', 'python_core.mock_ws_server', '--port', port], {
  cwd: rootDir,
  stdio: 'inherit',
});

pyProcess.on('error', (err) => {
  console.error('[SparkShield] Failed to launch Python mock stream:', err.message);
  process.exit(1);
});

pyProcess.on('exit', (code) => {
  console.log(`[SparkShield] Mock WebSocket server exited with code ${code}`);
  process.exit(code || 0);
});

process.on('SIGINT', () => {
  pyProcess.kill('SIGINT');
});
