// Trusted prototype control code, NOT an isolation boundary. qjs CLI still has module loader capabilities.
import * as __controlStd from 'qjs:std';
import * as __controlOs from 'qjs:os';
import { invokeTrustedFixture as __controlInvoke } from './fixture.mjs';
const __controlJson = JSON.stringify;
function __controlRead(count) {
    const buffer = new ArrayBuffer(count);
    let offset = 0;
    while (offset < count) {
        const size = __controlOs.read(__controlStd.in.fileno(), buffer, offset, count - offset);
        if (size <= 0) throw new Error('truncated control frame');
        offset += size;
    }
    return new Uint8Array(buffer);
}
const __controlHeader = __controlRead(4);
const __controlLength = new DataView(__controlHeader.buffer).getUint32(0, false);
if (__controlLength < 1 || __controlLength > 131072) throw new Error('invalid control length');
const __controlInput = __controlRead(__controlLength);
// Backend sends UTF-8 with ensure-ASCII encoding; no guest-controlled JSON decoding here.
let __controlText = '';
for (let i = 0; i < __controlInput.length; i++) __controlText += String.fromCharCode(__controlInput[i]);
const __controlRequest = JSON.parse(__controlText);
function __controlSend(value) {
    const text = __controlJson(value);
    // IDs, coordinates and fixed diagnostics are ASCII; reject anything else in this prototype envelope.
    if (/[^\x00-\x7f]/.test(text) || text.length > 4096) throw new Error('control output limit');
    const bytes = new Uint8Array(4 + text.length);
    new DataView(bytes.buffer).setUint32(0, text.length, false);
    for (let i = 0; i < text.length; i++) bytes[4 + i] = text.charCodeAt(i);
    let offset = 0;
    while (offset < bytes.length) {
        const written = __controlOs.write(__controlStd.out.fileno(), bytes.buffer, offset, bytes.length - offset);
        if (written <= 0) throw new Error('control write failed');
        offset += written;
    }
}
__controlSend({executionId: __controlRequest.executionId, stage: 'STARTED'});
const __controlGoHeader = __controlRead(4);
const __controlGoLength = new DataView(__controlGoHeader.buffer).getUint32(0, false);
if (__controlGoLength < 1 || __controlGoLength > 256) throw new Error('invalid GO frame');
const __controlGoBytes = __controlRead(__controlGoLength);
let __controlGoText = '';
for (let i = 0; i < __controlGoBytes.length; i++) __controlGoText += String.fromCharCode(__controlGoBytes[i]);
const __controlGo = JSON.parse(__controlGoText);
if (__controlGo.executionId !== __controlRequest.executionId || __controlGo.stage !== 'GO') throw new Error('bad GO correlation');
// Compilation/module initialization occurs in bootstrap. Invocation/return extraction use the guest wall budget.
try {
