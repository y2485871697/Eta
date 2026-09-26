// Run with: node --test vd-server-go/index.test.cjs
// No DOM package, network, Android process, or service is needed. Execute the
// actual embedded page script against a small DOM/fetch double, not a rewrite.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { Blob } = require('node:buffer');

const html = fs.readFileSync(__dirname + '/index.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const TOKEN = 'preview-only_TEST-token';
const HASH = '#eta-preview=45678.' + TOKEN;
// Identity is an opaque HTTP header value, not a URL query parameter.
const A = { displayId: 7, uniqueId: 'virtual:eta/A?x=1&y=two', phase: 'active' };
const B = { displayId: 11, uniqueId: 'virtual:eta/B', phase: 'held' };
const PNG = Uint8Array.from(Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=', 'base64'));
const JPEG = Uint8Array.of(255, 216, 255, 217);
const tick = () => new Promise(resolve => setImmediate(resolve));
async function settle() { for (let i = 0; i < 12; i++) await tick(); }
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
function element() {
    return {
        style: {}, disabled: false, hidden: false, value: '', textContent: '', src: '', children: [],
        removeAttribute(name) { if (name === 'src') this.src = ''; },
        replaceChildren() { this.children = []; this.value = ''; },
        appendChild(child) { this.children.push(child); },
        decode: async () => {}
    };
}
function response(status, data, headers = {}) {
    return {
        status, ok: status >= 200 && status < 300,
        headers: { get: name => Object.entries(headers).find(([key]) => key.toLowerCase() === name.toLowerCase())?.[1] ?? null },
        json: async () => data
    };
}
function list(displays = [A, B]) { return response(200, { ok: true, displays }); }
function frame(display = A, override = {}, chunks = [PNG]) {
    const res = response(200, null, {
        'Content-Type': 'image/png',
        'X-Eta-Display-Id': String(display.displayId),
        'X-Eta-Display-Unique-Id': display.uniqueId,
        ...override
    });
    let index = 0;
    res.body = { getReader: () => ({
        read: async () => index === chunks.length ? { done: true } : { done: false, value: chunks[index++] },
        cancel: async () => {}
    }) };
    return res;
}
function setup({ hash = HASH, origin = 'http://127.0.0.1:3070', fetch: handler = () => list(), confirm = () => true } = {}) {
    const elements = Object.fromEntries(['status-display', 'snapshot-img', 'empty-tip', 'btn-toggle',
        'preview-mode', 'eta-display', 'eta-display-controls'].map(id => [id, element()]));
    const footer = element();
    const events = {}, requests = [], images = [], created = [], revoked = [], timers = new Map();
    const location = { hash, pathname: '/', search: '', origin };
    const historyCalls = [], confirmations = [];
    let timerID = 0;
    const sandbox = {
        location,
        history: { replaceState(...args) { historyCalls.push(args); location.hash = ''; } },
        document: {
            getElementById: id => elements[id],
            querySelector: selector => selector === '.footer-note' ? footer : element(),
            createElement: () => element()
        },
        window: { addEventListener(name, callback) { events[name] = callback; }, confirm(message) { confirmations.push(message); return confirm(message); } },
        fetch(url, options = {}) {
            assert.equal(location.hash.includes(TOKEN), false, 'fragment must be erased before first request');
            requests.push({ url, options });
            return Promise.resolve(handler(url, options));
        },
        AbortController, Blob, Uint8Array,
        URL: {
            createObjectURL(blob) { const url = 'blob:test/' + created.length; created.push({ url, blob }); return url; },
            revokeObjectURL(url) { revoked.push(url); }
        },
        Image: function () { const img = element(); images.push(img); return img; },
        // Tests never run wall-clock timers. A timeout can be fired explicitly;
        // the only allowed timer is a bounded request deadline, not polling.
        setTimeout(callback, ms) { const id = ++timerID; timers.set(id, { callback, ms }); return id; },
        clearTimeout(id) { timers.delete(id); }
    };
    Object.defineProperty(sandbox, 'localStorage', { get() { throw new Error('credential persistence'); } });
    Object.defineProperty(sandbox, 'sessionStorage', { get() { throw new Error('credential persistence'); } });
    const context = vm.createContext(sandbox);
    vm.runInContext(script, context, { filename: 'index.html' });
    return {
        elements, events, requests, images, created, revoked, timers, historyCalls, location, confirmations,
        invoke: (name, ...args) => context[name](...args),
        async mode(value) { elements['preview-mode'].value = value; await context.changePreviewMode(); },
        async select(index) { elements['eta-display'].value = String(index); await context.etaSelectDisplay(); },
        message: () => elements['status-display'].textContent
    };
}
function previewFetch(url, options) {
    if (url.endsWith('/displays')) return list();
    const selected = options.headers['X-Eta-Display-Id'] === String(B.displayId) ? B : A;
    return frame(selected);
}
function assertNoLegacy(page) {
    assert.ok(page.requests.every(request => request.url.startsWith('http://127.0.0.1:45678/eta-preview/')));
}

test('initial Eta entry consumes hash, fetches list + authenticated identity-bound PNG, never polls', async () => {
    const page = setup({ fetch: previewFetch });
    await settle();
    assert.deepEqual(page.historyCalls, [[null, '', '/']]);
    assert.equal(page.elements['preview-mode'].value, 'eta');
    assert.equal(page.elements['btn-toggle'].disabled, true);
    assert.equal(page.elements['eta-display'].children.length, 3);
    assert.equal(page.requests.length, 2);
    assert.equal(page.requests[0].url, 'http://127.0.0.1:45678/eta-preview/displays');
    assert.deepEqual(Object.keys(page.requests[0].options.headers), ['Authorization']);
    assert.equal(page.requests[1].url, 'http://127.0.0.1:45678/eta-preview/frame');
    assert.equal(page.requests[1].options.headers['X-Eta-Display-Id'], String(A.displayId));
    assert.equal(page.requests[1].options.headers['X-Eta-Display-Unique-Id'], A.uniqueId);
    for (const { url, options } of page.requests) {
        assert.equal(url.includes(TOKEN), false);
        assert.equal(options.headers.Authorization, 'Bearer ' + TOKEN);
        assert.equal(options.mode, 'cors');
        assert.equal(options.credentials, 'omit');
        assert.equal(options.redirect, 'error');
        assert.equal(options.referrerPolicy, 'no-referrer');
        assert.equal(options.method, 'GET');
    }
    assert.equal(page.created[0].blob.type, 'image/png');
    assert.deepEqual(new Uint8Array(await page.created[0].blob.arrayBuffer()), PNG);
    assert.equal(page.elements['snapshot-img'].style.display, 'block');
    assert.match(page.message(), /Display 7.*只读快照/);
    assert.equal(page.timers.size, 0);
    assert.equal(page.events.pageshow, undefined);
    await page.invoke('toggleScreen');
    assert.equal(page.requests.length, 2, 'disabled toggle cannot send old commands even when invoked directly');
    await page.invoke('refreshSnapshot');
    assert.equal(page.requests.length, 4, 'manual refresh reloads both list and frame');
    assertNoLegacy(page);
});

test('display selection fetches only that identity and releases previous Blob URL', async () => {
    const page = setup({ fetch: previewFetch });
    await settle();
    await page.select(1);
    assert.equal(page.requests.length, 3);
    assert.equal(page.requests[2].url, 'http://127.0.0.1:45678/eta-preview/frame');
    assert.equal(page.requests[2].options.headers['X-Eta-Display-Id'], String(B.displayId));
    assert.equal(page.requests[2].options.headers['X-Eta-Display-Unique-Id'], B.uniqueId);
    assert.match(page.message(), /Display 11/);
    assert.deepEqual(page.revoked, ['blob:test/0']);
    page.events.pagehide();
    assert.deepEqual(page.revoked, ['blob:test/0', 'blob:test/1']);
    assert.equal(page.elements['snapshot-img'].src, '');
    assert.equal(page.timers.size, 0);
    assertNoLegacy(page);
});

test('no fragment preserves legacy mode; selecting unauthenticated Eta shows explicit connection guidance', async () => {
    const page = setup({ hash: '', fetch: () => response(200, { status: 'stopped' }) });
    await settle();
    assert.equal(page.elements['preview-mode'].value, 'module');
    assert.equal(page.requests[0].url, '/api/status');
    assert.equal(page.elements['btn-toggle'].textContent, '开启副屏');
    assert.equal(page.elements['btn-toggle'].disabled, false);
    await page.mode('eta');
    assert.equal(page.message(), '未连接代鱼预览，请从代鱼副屏入口打开');
    assert.equal(page.elements['btn-toggle'].disabled, true);
    await page.invoke('toggleScreen');
    await page.invoke('refreshSnapshot');
    assert.equal(page.requests.length, 1, 'must not scan ports, fallback, or start/stop');
    assert.doesNotMatch(page.message(), /休眠/);
});

test('invalid fragment and non-local page origin fail closed and scrub the fragment', async () => {
    for (const options of [{ hash: '#eta-preview=0.bad' }, { hash: '#eta-preview=65536.bad' },
        { hash: '#eta-preview=45678.bad%0d%0aHeader' }, { origin: 'http://remote:3070' }]) {
        const page = setup(options);
        await settle();
        assert.equal(page.historyCalls.length, 1);
        assert.equal(page.location.hash, '');
        assert.equal(page.requests.length, 0);
        assert.match(page.message(), /预览链接无效/);
    }
});

test('empty, absent, malformed lists, 401 and 410 remain distinct with no legacy fallback', async () => {
    const cases = [
        [list([]), /列表为空/],
        [response(404), /无法获取代鱼副屏列表/],
        [response(200, { ok: true }), /列表响应无效/],
        [list([{ ...A, uniqueId: '' }]), /列表响应无效/],
        [list([A, A]), /列表响应无效/],
        [response(401), /授权已失效（401）/],
        [response(410), /已关闭（410）/]
    ];
    for (const [result, message] of cases) {
        const page = setup({ fetch: () => result });
        await settle();
        assert.match(page.message(), message);
        assert.equal(page.created.length, 0);
        assert.equal(page.requests.length, 1);
        assert.equal(page.elements['btn-toggle'].disabled, true);
        assertNoLegacy(page);
        if (result.status === 401) {
            await page.invoke('refreshSnapshot');
            assert.equal(page.requests.length, 1, 'expired credential is discarded');
            assert.match(page.message(), /401/);
        }
    }
});

test('frame HTTP failures, mismatched or unexposed identity headers and JPEG are rejected before rendering', async () => {
    const cases = [
        [response(401), /401/], [response(410), /410/], [response(404), /暂无可用画面/],
        [frame(A, { 'X-Eta-Display-Id': '11' }), /身份不匹配/],
        [frame(A, { 'X-Eta-Display-Id': null }), /身份不匹配/],
        [frame(A, { 'X-Eta-Display-Unique-Id': B.uniqueId }), /身份不匹配/],
        [frame(A, { 'X-Eta-Display-Unique-Id': null }), /身份不匹配/],
        [frame(A, { 'Content-Type': 'image/jpeg' }, [JPEG]), /PNG/],
        [frame(A, { 'Content-Type': 'image/jpeg' }), /PNG/],
        [frame(A, { 'Content-Length': String(6 * 1024 * 1024 + 1) }), /PNG/]
    ];
    for (const [result, message] of cases) {
        const page = setup({ fetch: url => url.endsWith('/displays') ? list() : result });
        await settle();
        assert.match(page.message(), message);
        assert.equal(page.created.length, 0);
        assert.equal(page.elements['snapshot-img'].style.display, 'none');
        assertNoLegacy(page);
    }
});

test('PNG requires all eight signature bytes, permits split chunks, and enforces the streaming size limit', async () => {
    const valid = setup({ fetch: url => url.endsWith('/displays') ? list() :
        frame(A, { 'Content-Type': 'image/png; charset=binary' }, [PNG.slice(0, 3), PNG.slice(3)]) });
    await settle();
    assert.equal(valid.created.length, 1);
    assert.equal(valid.created[0].blob.type, 'image/png');
    assert.deepEqual(new Uint8Array(await valid.created[0].blob.arrayBuffer()), PNG);
    assert.equal(valid.elements['snapshot-img'].style.display, 'block');

    const badSignature = PNG.slice();
    badSignature[7] = 0;
    for (const chunks of [[JPEG], [], [PNG.slice(0, 7)], [badSignature],
        [PNG, new Uint8Array(6 * 1024 * 1024)]]) {
        const page = setup({ fetch: url => url.endsWith('/displays') ? list() : frame(A, {}, chunks) });
        await settle();
        assert.match(page.message(), /PNG/);
        assert.equal(page.created.length, 0);
        assert.equal(page.elements['snapshot-img'].style.display, 'none');
        assertNoLegacy(page);
    }
});

test('display switch aborts old request and ignores late response even if fetch ignores abort', async () => {
    const old = deferred();
    const page = setup({ fetch: (url, options) => url.endsWith('/displays') ? list() :
        options.headers['X-Eta-Display-Id'] === '7' ? old.promise : frame(B) });
    await settle();
    const oldSignal = page.requests[1].options.signal;
    await page.select(1);
    assert.equal(oldSignal.aborted, true);
    old.resolve(frame(A));
    await settle();
    assert.match(page.message(), /Display 11/);
    assert.equal(page.created.length, 1);
    assert.equal(page.timers.size, 0);
});

test('late PNG body and late decode cannot contaminate a new selection', async () => {
    const body = deferred();
    const oldFrame = frame(A);
    oldFrame.body = { getReader: () => {
        let sent = false;
        return {
            read: () => sent ? Promise.resolve({ done: true }) : (sent = true, body.promise),
            cancel: async () => {}
        };
    } };
    let firstFrame = true;
    const page = setup({ fetch: (url, options) => {
        if (url.endsWith('/displays')) return list();
        if (options.headers['X-Eta-Display-Id'] !== '7') return frame(B);
        if (firstFrame) { firstFrame = false; return oldFrame; }
        return frame(A);
    } });
    await settle();
    await page.select(1);
    body.resolve({ done: false, value: PNG });
    await settle();
    assert.match(page.message(), /Display 11/);
    assert.equal(page.created.length, 1);

    const decode = deferred();
    page.elements['snapshot-img'].decode = () => decode.promise;
    const pending = page.select(0);
    await settle();
    assert.equal(page.created.length, 2, 'first selection must reach image decoding');
    page.elements['snapshot-img'].decode = async () => {};
    await page.select(1);
    decode.resolve();
    await pending;
    assert.match(page.message(), /Display 11/);
    assert.equal(page.elements['snapshot-img'].src, 'blob:test/2');
    assert.deepEqual(page.revoked, ['blob:test/0', 'blob:test/1']);
});

test('same numeric display ID with a replaced uniqueId requires explicit reselection', async () => {
    let replaced = false;
    const replacement = { ...A, uniqueId: 'replacement-owner' };
    const page = setup({ fetch: url => url.endsWith('/displays') ? list(replaced ? [replacement] : [A]) : frame(A) });
    await settle();
    replaced = true;
    await page.invoke('refreshSnapshot');
    assert.match(page.message(), /身份已变化/);
    assert.equal(page.requests.length, 3, 'must not silently render the recycled display ID');
    assert.equal(page.elements['snapshot-img'].style.display, 'none');
    assert.equal(page.elements['eta-display'].value, '');
});

test('switching mode suppresses late Eta frame and late legacy status/screenshot callbacks', async () => {
    const old = deferred();
    const page = setup({ fetch: url => url === '/api/status' ? response(200, { status: 'stopped' }) :
        url.endsWith('/displays') ? list() : old.promise });
    await settle();
    await page.mode('module');
    old.resolve(frame(A));
    await settle();
    assert.equal(page.elements['btn-toggle'].textContent, '开启副屏');
    assert.equal(page.created.length, 0);
    assert.match(page.message(), /模块副屏/);

    const status = deferred();
    const legacy = setup({ hash: '', fetch: () => status.promise });
    await legacy.mode('eta');
    status.resolve(response(200, { status: 'running', display_id: 4 }));
    await settle();
    assert.equal(legacy.images.length, 0, 'late module status must not start a screenshot');
    assert.equal(legacy.elements['btn-toggle'].disabled, true);
    assert.match(legacy.message(), /未连接代鱼预览/);

    const screenshot = setup({ hash: '', fetch: () => response(200, { status: 'running', display_id: 4 }) });
    await settle();
    assert.equal(screenshot.images.length, 1);
    const lateLoad = screenshot.images[0].onload;
    await screenshot.mode('eta');
    lateLoad();
    assert.equal(screenshot.elements['snapshot-img'].src, '');
    assert.equal(screenshot.requests.length, 1);
    assert.match(screenshot.message(), /未连接代鱼预览/);
});

test('legacy start/stop remain operational and never receive Eta identity or token', async () => {
    let running = false;
    const page = setup({ hash: '', fetch: url => {
        if (url === '/api/start') running = true;
        if (url === '/api/stop') running = false;
        return response(200, { status: running ? 'running' : 'stopped', display_id: 4 });
    } });
    await settle();
    await page.invoke('toggleScreen');
    assert.equal(page.requests[1].url, '/api/start');
    assert.equal(page.requests[1].options.method, 'POST');
    assert.equal(page.images.length, 1);
    assert.match(page.images[0].src, /^\/api\/screenshot\?t=/);
    page.images[0].onload();
    await settle();
    assert.equal(page.elements['snapshot-img'].style.display, 'block');
    await page.invoke('toggleScreen');
    assert.ok(page.requests.some(request => request.url === '/api/stop' && request.options.method === 'POST'));
    for (const request of page.requests) {
        assert.equal(request.options.headers, undefined);
        assert.doesNotMatch(request.url, /displayId|uniqueId|eta|token/);
    }
    assert.equal(page.elements['btn-toggle'].textContent, '开启副屏');
});

test('network/CORS failure and timeout are manual-retry errors, not sleeping or automatic fallback', async () => {
    const disconnected = setup({ fetch: () => Promise.reject(new TypeError('CORS')) });
    await settle();
    assert.match(disconnected.message(), /无法连接代鱼预览/);
    assert.doesNotMatch(disconnected.message(), /休眠/);
    assert.equal(disconnected.requests.length, 1);
    assert.equal(disconnected.timers.size, 0);

    const page = setup({ fetch: (_, options) => new Promise((resolve, reject) => {
        options.signal.addEventListener('abort', () => {
            const error = new Error('timeout'); error.name = 'AbortError'; reject(error);
        });
    }) });
    assert.equal(page.timers.size, 1);
    const timer = [...page.timers.values()][0];
    assert.equal(timer.ms, 8000);
    timer.callback();
    await settle();
    assert.match(page.message(), /请求超时/);
    assert.equal(page.requests.length, 1);
    assert.equal(page.timers.size, 0);
});


const CONTROL = 'c'.repeat(64);
const CONTROL_HASH = HASH + '&eta_control=' + CONTROL;
const closeResult = (outcome, reason='', extra={}, status=200) =>
    response(status, {outcome, reason, nonce:null, expiresInMs:null, ...extra}, {'Content-Type':'application/json'});
const preparedResult = () => closeResult('prepared','',{nonce:'once-123',expiresInMs:30000});
function closeFetch(url, options) {
    if (url.endsWith('/close/prepare')) return preparedResult();
    if (url.endsWith('/close/commit')) return closeResult('closed_confirmed');
    return previewFetch(url,options);
}
const closeRequests = page => page.requests.filter(r => r.url.includes('/close/'));

test('manual close uses independent grant and single prepare-confirm-commit, never legacy stop', async () => {
    const page=setup({hash:CONTROL_HASH,fetch:closeFetch});await settle();
    assert.equal(page.elements['btn-toggle'].disabled,false);
    await page.invoke('toggleScreen');
    const requests=closeRequests(page);assert.equal(requests.length,2);
    assert.ok(requests[0].url.endsWith('/close/prepare'));assert.ok(requests[1].url.endsWith('/close/commit'));
    for(const r of requests) {
        assert.equal(r.options.method,'POST');assert.equal(r.options.headers.Authorization,'Bearer '+TOKEN);
        assert.equal(r.options.headers['X-Eta-Control-Token'],CONTROL);
        assert.equal(r.options.headers['X-Eta-Display-Id'],String(A.displayId));
        assert.equal(r.options.headers['X-Eta-Display-Unique-Id'],A.uniqueId);
        assert.equal(r.options.body,undefined);assert.ok(!r.url.includes(TOKEN)&&!r.url.includes(CONTROL));
    }
    assert.equal(requests[0].options.headers['X-Eta-Close-Nonce'],undefined);
    assert.equal(requests[1].options.headers['X-Eta-Close-Nonce'],'once-123');
    assert.equal(page.confirmations.length,1);assert.match(page.message(),/已确认.*均已关闭/);
    assert.equal(page.elements['btn-toggle'].disabled,true);assertNoLegacy(page);
});

test('read-only and malformed control links never send close requests',async()=>{
    const read=setup({fetch:closeFetch});await settle();await read.invoke('toggleScreen');assert.equal(closeRequests(read).length,0);
    for(const hash of [HASH+'&eta_control=x',CONTROL_HASH+'&eta_control='+CONTROL,CONTROL_HASH+'&other=x']) {
        const page=setup({hash,fetch:closeFetch});await settle();await page.invoke('toggleScreen');
        assert.equal(closeRequests(page).length,0);assert.equal(page.location.hash,'');assert.equal(page.elements['btn-toggle'].disabled,true);
    }
});

test('manual close remains available on an authenticated empty black frame',async()=>{
    const page=setup({hash:CONTROL_HASH,fetch:(u,o)=>u.endsWith('/frame')?response(404):closeFetch(u,o)});
    await settle();assert.equal(page.elements['btn-toggle'].disabled,false);await page.invoke('toggleScreen');
    assert.match(page.message(),/已确认/);assertNoLegacy(page);
});

test('cancel and occupied preparation never send commit',async()=>{
    const cancel=setup({hash:CONTROL_HASH,fetch:closeFetch,confirm:()=>false});await settle();await cancel.invoke('toggleScreen');
    assert.equal(closeRequests(cancel).length,1);assert.match(cancel.message(),/已取消/);
    const occupied=setup({hash:CONTROL_HASH,fetch:(u,o)=>u.endsWith('/close/prepare')?closeResult('blocked','SOURCE_NOT_EMPTY',{},409):closeFetch(u,o)});
    await settle();await occupied.invoke('toggleScreen');assert.equal(closeRequests(occupied).length,1);
    assert.equal(occupied.confirmations.length,0);assert.match(occupied.message(),/SOURCE_NOT_EMPTY/);
});

test('uncertain commit disables resubmission even after manual refresh',async()=>{
    const page=setup({hash:CONTROL_HASH,fetch:(u,o)=>u.endsWith('/close/commit')?closeResult('closed_unconfirmed','RELEASE_UNCONFIRMED',{},503):closeFetch(u,o)});
    await settle();await page.invoke('toggleScreen');assert.match(page.message(),/未确认/);
    await page.invoke('toggleScreen');await page.invoke('refreshSnapshot');await page.invoke('toggleScreen');
    assert.equal(closeRequests(page).length,2);assert.equal(page.elements['btn-toggle'].disabled,true);assertNoLegacy(page);
});

test('lost commit response is never treated as success or automatically retried',async()=>{
    const page=setup({hash:CONTROL_HASH,fetch:(u,o)=>u.endsWith('/close/commit')?Promise.reject(new Error('network')):closeFetch(u,o)});
    await settle();await page.invoke('toggleScreen');await page.invoke('toggleScreen');
    assert.match(page.message(),/未确认/);assert.equal(closeRequests(page).length,2);assert.equal(page.elements['btn-toggle'].disabled,true);
});

test('double click and pagehide cannot dispatch a stale prepared confirmation',async()=>{
    const pending=deferred();
    const page=setup({hash:CONTROL_HASH,fetch:(u,o)=>u.endsWith('/close/prepare')?pending.promise:closeFetch(u,o)});
    await settle();const operation=page.invoke('toggleScreen');await settle();
    await page.invoke('toggleScreen');assert.equal(closeRequests(page).length,1);
    page.events.pagehide();pending.resolve(preparedResult());await operation;
    assert.equal(closeRequests(page).length,1);assert.equal(page.confirmations.length,0);
});

test('preparation failure and malformed nonce do not send release',async()=>{
    for(const result of [()=>Promise.reject(new Error('cors')),()=>closeResult('prepared','',{nonce:'bad nonce',expiresInMs:30000})]) {
        const page=setup({hash:CONTROL_HASH,fetch:(u,o)=>u.endsWith('/close/prepare')?result():closeFetch(u,o)});
        await settle();await page.invoke('toggleScreen');assert.equal(closeRequests(page).length,1);assert.equal(page.confirmations.length,0);
        assert.match(page.message(),/未发送关闭请求/);assert.equal(page.elements['btn-toggle'].disabled,false);
    }
});
