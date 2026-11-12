/*
 * Boatcast Live: a direct browser-to-browser link (WebRTC data channels), paired by QR codes.
 * No server, no app, nothing installed: the presenter's browser and each phone's browser
 * talk straight to each other over the shared Wi-Fi.
 *
 * Pairing needs one message each way:
 *   offer  (presenter -> phone): shown as a QR code, read by the phone's camera
 *   answer (phone -> presenter): shown as a QR code on the phone, read by the computer's camera
 * Only what WebRTC needs is sent (ICE user/password, DTLS fingerprint, local addresses),
 * about 150 characters; the full session description is rebuilt from a fixed template.
 *
 * Two channels per phone: "ctl" for slide changes and timing checks, "bulk" for slide
 * images, so a slide change is never stuck behind a picture that is still downloading.
 */
(function (g) {
  'use strict';
  var V = 'B1';

  function b64u(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function unb64u(s) {
    s = s.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    var bin = atob(s), out = [];
    for (var i = 0; i < bin.length; i++) out.push(bin.charCodeAt(i));
    return out;
  }
  function rid() {
    return Math.floor(Math.random() * 1679616).toString(36); // up to 4 base-36 characters
  }

  function waitGathering(pc, ms) {
    return new Promise(function (res) {
      if (pc.iceGatheringState === 'complete') return res();
      var t = setTimeout(res, ms);
      pc.addEventListener('icegatheringstatechange', function () {
        if (pc.iceGatheringState === 'complete') { clearTimeout(t); res(); }
      });
    });
  }

  // Local addresses worth sending: private IPv4 first, then any IPv4, then hidden (.local)
  // names. Mobile-data and IPv6 addresses are useless on a Wi-Fi with no router, and every
  // character makes the QR code denser.
  function rank(addr) {
    if (/\.local$/i.test(addr)) return 3;
    if (!/^\d+\.\d+\.\d+\.\d+$/.test(addr)) return 9;
    if (/^192\.168\.56\./.test(addr)) return 1; // VirtualBox's built-in network: never the Wi-Fi
    if (/^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(addr)) return 0;
    if (/^(127\.|169\.254\.)/.test(addr)) return 8;
    return 2;
  }

  function pack(role, id, sdp) {
    var ufrag = /a=ice-ufrag:(\S+)/.exec(sdp), pwd = /a=ice-pwd:(\S+)/.exec(sdp);
    var fp = /a=fingerprint:sha-256 (\S+)/i.exec(sdp), setup = /a=setup:(\w+)/.exec(sdp);
    if (!ufrag || !pwd || !fp || !setup) throw new Error('This browser gave an unexpected connection description');
    var bytes = fp[1].split(':').map(function (h) { return parseInt(h, 16); });
    var seen = {}, cands = [];
    sdp.split(/\r?\n/).forEach(function (line) {
      var m = /^a=candidate:\S+ 1 udp \d+ (\S+) (\d+) typ host/i.exec(line);
      if (!m || seen[m[1]] || rank(m[1]) > 3) return;
      seen[m[1]] = 1;
      cands.push({ a: m[1], p: m[2], r: rank(m[1]) });
    });
    cands.sort(function (x, y) { return x.r - y.r; });
    // Laptops often have extra private addresses (VPN, virtual machines); keep enough that
    // the Wi-Fi one is always included.
    var list = cands.slice(0, 6).map(function (c) { return c.a + ':' + c.p; }).join(';');
    var s = { actpass: 'p', active: 'a', passive: 's' }[setup[1]] || 'a';
    return [V, role, id, s, ufrag[1], pwd[1], b64u(bytes), list].join(',');
  }

  function unpack(text) {
    var i = String(text || '').indexOf(V + ',');
    if (i < 0) return null;
    var f = decodeURIComponent(text.slice(i)).trim().split(',');
    if (f.length !== 8 || (f[1] !== 'o' && f[1] !== 'a')) return null;
    var fp;
    try { fp = unb64u(f[6]); } catch (e) { return null; }
    if (fp.length !== 32) return null;
    var cands = f[7] ? f[7].split(';').map(function (c) {
      var k = c.lastIndexOf(':');
      return { a: c.slice(0, k), p: c.slice(k + 1) };
    }) : [];
    return {
      role: f[1], id: f[2],
      setup: { p: 'actpass', a: 'active', s: 'passive' }[f[3]] || 'active',
      ufrag: f[4], pwd: f[5],
      fp: fp.map(function (b) { return (b < 16 ? '0' : '') + b.toString(16).toUpperCase(); }).join(':'),
      cands: cands
    };
  }

  function buildSdp(sig) {
    var L = [
      'v=0',
      'o=- ' + (1 + Math.floor(Math.random() * 1e9)) + ' 2 IN IP4 127.0.0.1',
      's=-', 't=0 0', 'a=group:BUNDLE 0',
      'm=application 9 UDP/DTLS/SCTP webrtc-datachannel',
      'c=IN IP4 0.0.0.0',
      'a=ice-ufrag:' + sig.ufrag, 'a=ice-pwd:' + sig.pwd, 'a=ice-options:trickle',
      'a=fingerprint:sha-256 ' + sig.fp, 'a=setup:' + sig.setup, 'a=mid:0',
      'a=sctp-port:5000', 'a=max-message-size:262144'
    ];
    sig.cands.forEach(function (c, i) {
      L.push('a=candidate:' + (i + 1) + ' 1 udp ' + (2122260223 - i) + ' ' + c.a + ' ' + c.p + ' typ host');
    });
    L.push('a=end-of-candidates');
    return L.join('\r\n') + '\r\n';
  }

  function channels(pc) {
    var ctl = pc.createDataChannel('ctl', { negotiated: true, id: 0 });
    var bulk = pc.createDataChannel('bulk', { negotiated: true, id: 1 });
    bulk.binaryType = 'arraybuffer';
    bulk.bufferedAmountLowThreshold = 64 * 1024;
    return { ctl: ctl, bulk: bulk };
  }

  function newPc() {
    return new RTCPeerConnection({ iceServers: [] }); // same Wi-Fi only: no outside servers
  }

  /** Presenter: a new offer. Returns {id, code, pc, ctl, bulk, created}. */
  async function offer() {
    var pc = newPc(), ch = channels(pc), id = rid();
    await pc.setLocalDescription(await pc.createOffer());
    await waitGathering(pc, 2500);
    return { id: id, code: pack('o', id, pc.localDescription.sdp), pc: pc, ctl: ch.ctl, bulk: ch.bulk, created: Date.now() };
  }

  /** Presenter: complete a pending offer with the phone's answer code. */
  async function accept(pending, sig) {
    await pending.pc.setRemoteDescription({ type: 'answer', sdp: buildSdp(sig) });
  }

  /** Phone: answer an offer code. Returns {id, code, pc, ctl, bulk}. */
  async function answer(sig) {
    var pc = newPc(), ch = channels(pc);
    await pc.setRemoteDescription({ type: 'offer', sdp: buildSdp(sig) });
    await pc.setLocalDescription(await pc.createAnswer());
    await waitGathering(pc, 2500);
    return { id: sig.id, code: pack('a', sig.id, pc.localDescription.sdp), pc: pc, ctl: ch.ctl, bulk: ch.bulk };
  }

  /**
   * Wait until a channel can take more. Pictures and slide changes share one connection,
   * so pictures are fed in small amounts (256 KB queued at most) to keep slide changes quick.
   */
  function drain(dc) {
    if (dc.bufferedAmount < 256 * 1024) return Promise.resolve();
    return new Promise(function (res) {
      var done = function () { dc.removeEventListener('bufferedamountlow', done); res(); };
      dc.addEventListener('bufferedamountlow', done);
    });
  }

  // ---------------------------------------------------------------- QR codes

  /** An <svg> QR code for text, sized by CSS. */
  function qrSvg(text) {
    var q = g.qrcode(0, 'M');
    q.addData(text, 'Byte');
    q.make();
    var n = q.getModuleCount(), m = 4, d = '';
    for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) if (q.isDark(r, c)) d += 'M' + (c + m) + ' ' + (r + m) + 'h1v1h-1z';
    var s = n + 2 * m;
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + s + ' ' + s + '" shape-rendering="crispEdges">' +
      '<rect width="' + s + '" height="' + s + '" fill="#fff"/><path d="' + d + '" fill="#000"/></svg>';
  }

  /** Open a camera into a <video>. facing: 'environment' (phone back camera) or 'user'. */
  async function camera(video, facing) {
    var stream = await navigator.mediaDevices.getUserMedia({
      audio: false, video: { facingMode: facing, width: { ideal: 1280 }, height: { ideal: 720 } }
    });
    video.srcObject = stream;
    video.setAttribute('playsinline', '');
    video.muted = true;
    await video.play().catch(function () {});
    return stream;
  }

  /** Read QR codes from a playing <video> a few times a second. Returns stop(). */
  function scan(video, onText) {
    var cv = document.createElement('canvas'), cx = cv.getContext('2d', { willReadFrequently: true });
    var stopped = false, last = '';
    function tick() {
      if (stopped) return;
      var w = video.videoWidth, h = video.videoHeight;
      if (w && h) {
        var k = Math.min(1, 800 / Math.max(w, h));
        cv.width = Math.round(w * k); cv.height = Math.round(h * k);
        cx.drawImage(video, 0, 0, cv.width, cv.height);
        var img = cx.getImageData(0, 0, cv.width, cv.height);
        var r = g.jsQR(img.data, img.width, img.height, { inversionAttempts: 'attemptBoth' });
        if (r && r.data && r.data !== last) { last = r.data; onText(r.data); }
      }
      setTimeout(tick, 180);
    }
    tick();
    return function () { stopped = true; };
  }

  function stopStream(stream) {
    if (stream) stream.getTracks().forEach(function (t) { t.stop(); });
  }

  /** Short device label from a browser's user agent. */
  function label(ua) {
    ua = ua || '';
    var os = /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Android/.test(ua) ? 'Android'
      : /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : 'Device';
    var br = /SamsungBrowser/.test(ua) ? 'Samsung Internet' : /EdgA?\//.test(ua) ? 'Edge'
      : /Firefox|FxiOS/.test(ua) ? 'Firefox' : /CriOS|Chrome/.test(ua) ? 'Chrome'
      : /Safari/.test(ua) ? 'Safari' : 'browser';
    return os + ' · ' + br;
  }

  g.BoatLive = {
    pack: pack, unpack: unpack, buildSdp: buildSdp, offer: offer, accept: accept, answer: answer,
    drain: drain, qrSvg: qrSvg, camera: camera, scan: scan, stopStream: stopStream, label: label
  };
})(window);
