(() => {
  if (window !== window.top || location.origin !== 'https://ruyisdk.org' ||
      !/^\/(?:(?:en|de|zh-Hans)\/)?tryruyi\/(?:imager|device)\/?$/.test(location.pathname)) return;

  const channel = window.ruyiAndroid;
  const requests = new Map();
  const boards = new Map();
  const usb = new EventTarget();
  const directories = new WeakMap();
  const nativeFetch = window.fetch.bind(window);
  let sequence = 0, handshake, selectedRoot;

  channel.onmessage = ({ data }) => {
    const message = JSON.parse(data);
    if (message.disconnect) {
      const device = boards.get(message.disconnect);
      if (device) {
        device.opened = false;
        boards.delete(message.disconnect);
        usb.dispatchEvent(Object.assign(new Event('disconnect'), { device }));
      }
      return;
    }
    const pending = requests.get(message.id);
    if (!pending) return;
    requests.delete(message.id);
    if (message.error) pending.reject(new DOMException(message.error, message.name || 'Error'));
    else pending.resolve(message.value);
  };
  function send(method, args, key) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      requests.set(id, { resolve, reject });
      try { channel.postMessage(JSON.stringify({ id, key, method, args })); }
      catch (error) { requests.delete(id); reject(error); }
    });
  }
  const key = () => handshake ??= send('hello', {});
  const call = async (method, args = {}) => send(method, args, await key());
  async function stream(ticket, options = {}) {
    if (!/^[\da-f-]{36}$/.test(ticket)) throw Error('Invalid stream ticket');
    return nativeFetch(`/.ruyi-android/${ticket}`, {
      method: 'GET', signal: options.signal, redirect: 'error', cache: 'no-store',
      credentials: 'omit', headers: { 'X-Ruyi-Session': await key() },
    });
  }
  const downloadPrefix = 'ruyi-image://download/';
  window.fetch = (input, options) => typeof input === 'string' && input.startsWith(downloadPrefix)
    ? stream(input.slice(downloadPrefix.length), options) : nativeFetch(input, options);

  function encode(bytes) {
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 8192)
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
    return btoa(binary);
  }
  async function chunks(value, append) {
    if (value instanceof Blob) {
      for (let offset = 0; offset < value.size; offset += 196608)
        await append(encode(new Uint8Array(await value.slice(offset, offset + 196608).arrayBuffer())));
      return;
    }
    const bytes = ArrayBuffer.isView(value) ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength) : new Uint8Array(value);
    for (let offset = 0; offset < bytes.length; offset += 196608) await append(encode(bytes.subarray(offset, offset + 196608)));
  }
  async function writable(root, parts) {
    const writer = await call('fileOpen', { root, parts });
    const sink = {
      write: value => chunks(value, data => call('fileAppend', { root, writer, data })),
      close: () => call('fileFinish', { root, writer, commit: true }),
      abort: () => call('fileFinish', { root, writer, commit: false }),
    };
    return Object.assign(new WritableStream(sink), { write: sink.write, close: sink.close });
  }
  function directory(root, parts, name) {
    async function lookup(child, folder, options) {
      const path = [...parts, child];
      await call('fileLookup', { root, parts: path, directory: folder, create: !!options?.create });
      return path;
    }
    const handle = {
      name, kind: 'directory',
      async getDirectoryHandle(child, options) { return directory(root, await lookup(child, true, options), child); },
      async getFileHandle(child, options) {
        const path = await lookup(child, false, options);
        return {
          name: child, kind: 'file', createWritable: () => writable(root, path),
          async getFile() {
            const response = await stream(await call('fileRead', { root, parts: path }));
            if (!response.ok) throw Error(`Cannot open ${child}: HTTP ${response.status}`);
            return new File([await response.blob()], child);
          },
        };
      },
    };
    directories.set(handle, { root, parts });
    return handle;
  }
  window.showDirectoryPicker = async () => {
    const { root, name } = await call('directory');
    selectedRoot = root;
    return directory(root, [], name);
  };
  Object.defineProperty(window, 'showOpenFilePicker', { configurable: true, value: undefined });

  function workerBootstrap(url) {
    importScripts(url);
    const run = self.onmessage;
    self.onmessage = async event => {
      const { outputPort, job } = event.data;
      if (!outputPort) return run.call(self, event);
      let reply;
      outputPort.onmessage = ({ data }) => data.error ? reply.reject(Error(data.error)) : reply.resolve();
      const request = (action, payload, transfer = []) => new Promise((resolve, reject) => {
        reply = { resolve, reject };
        outputPort.postMessage({ action, payload }, transfer);
      });
      function folder(path) {
        return {
          getDirectoryHandle: name => folder([...path, name]),
          getFileHandle: name => ({ async createWritable() {
            await request('open', [...path, name]);
            return {
              write(data) { const bytes = new Uint8Array(data); return request('write', bytes, [bytes.buffer]); },
              close: () => request('close'), abort: () => request('abort'),
            };
          } }),
        };
      }
      job.dir = folder([]);
      try { await run.call(self, event); }
      finally { outputPort.postMessage({ action: 'done' }); outputPort.close(); }
    };
  }
  const NativeWorker = window.Worker;
  window.Worker = class extends NativeWorker {
    constructor(script, options) {
      const url = new URL(script, location.href);
      const deviceWorker = url.origin === location.origin && /^\/(?:(?:en|de|zh-Hans)\/)?device-worker\.js$/.test(url.pathname);
      const bootstrap = deviceWorker && URL.createObjectURL(new Blob([
        `(${workerBootstrap.toString()})(${JSON.stringify(url.href)})`,
      ], { type: 'text/javascript' }));
      super(bootstrap || script, options);
      if (bootstrap) URL.revokeObjectURL(bootstrap);
      this.outputs = new Set();
    }
    postMessage(message, transfer) {
      const destination = directories.get(message?.job?.dir);
      if (!destination) return super.postMessage(message, transfer);
      const { port1, port2 } = new MessageChannel();
      const output = { port: port1, writer: null, stopped: false };
      this.outputs.add(output);
      port1.onmessage = async ({ data: { action, payload } }) => {
        try {
          if (action === 'done') { this.stopOutput(output); return; }
          if (action === 'open') {
            output.writer = await writable(destination.root, [...destination.parts, ...payload]);
            if (output.stopped) { await output.writer.abort(); return; }
          } else if (action === 'write') await output.writer.write(payload);
          else if (action === 'close' || action === 'abort') {
            const writer = output.writer; output.writer = null;
            await writer[action]();
          } else throw Error('Invalid extraction operation');
          port1.postMessage({});
        } catch (error) { port1.postMessage({ error: String(error) }); }
      };
      super.postMessage({ ...message, job: { ...message.job, dir: null }, outputPort: port2 }, [port2]);
    }
    stopOutput(output) {
      output.stopped = true; output.port.close();
      void output.writer?.abort().catch(() => {});
      this.outputs.delete(output);
    }
    terminate() { for (const output of this.outputs) this.stopOutput(output); super.terminate(); }
  };

  function board(info) {
    if (boards.has(info.id)) return boards.get(info.id);
    const id = info.id;
    const device = {
      ...info, configurations: [info.configuration], opened: false,
      async open() { await call('usbOpen', { id }); this.opened = true; },
      async close() { await call('usbClose', { id }); this.opened = false; },
      async claimInterface(number) {
        if (!this.opened || number !== this.configuration.interfaces[0].interfaceNumber) throw Error('Invalid fastboot interface');
      },
      async transferIn(endpoint, size) {
        const binary = atob(await call('usbIn', { id, endpoint, size }));
        return { status: 'ok', data: new DataView(Uint8Array.from(binary, byte => byte.charCodeAt(0)).buffer) };
      },
      async transferOut(endpoint, value) {
        let bytesWritten = 0;
        await chunks(value, async data => { bytesWritten += await call('usbOut', { id, endpoint, data }); });
        return { status: 'ok', bytesWritten };
      },
    };
    boards.set(id, device);
    return device;
  }
  usb.requestDevice = async () => board(await call('usbSelect'));
  usb.getDevices = async () => (await call('usbDevices')).map(board);
  Object.defineProperty(navigator, 'usb', { configurable: true, value: usb });
  Object.defineProperty(window, 'ruyiImager', { value: Object.freeze({
    getMirrorDownloadURL: url => call('mirror', { url }),
    listDisks: () => call('listDisks'),
    chooseDisk: (slot, id) => call('chooseDisk', { slot, id }),
    writeAndVerify: (id, image) => call('write', { id, image, root: selectedRoot }),
    setFlashing: active => call('flashing', { active }),
  }) });
})();
