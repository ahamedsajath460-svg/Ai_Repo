/* Maps the web Speech APIs onto the Android bridge (window.NativeSpeech) because Android WebView has
   neither SpeechRecognition nor a working speechSynthesis. Does nothing outside the app. */
(function () {
  /* Clipboard: WebView blocks clipboard.readText(), so route it to the app. */
  var C = window.NativeClip;
  if (C) {
    var clip = {
      readText: function () { return new Promise(function (ok) { ok(String(C.read() || "")); }); },
      writeText: function (t) { C.write(String(t)); return Promise.resolve(); }
    };
    try { Object.defineProperty(navigator, "clipboard", { value: clip, configurable: true }); } catch (e) {}
  }
  var N = window.NativeSpeech;
  if (!N) return;
  var recs = {}, utts = {}, rid = 0, uid = 0, curU = null;

  window.__nsEvt = function (kind, id, type, json) {
    var d = {}; try { d = JSON.parse(json || "{}"); } catch (e) {}
    if (kind === "rec") { var r = recs[id]; if (r) r._ev(type, d); }
    else if (kind === "tts") { var u = utts[id]; if (u) u._ev(type, d); }
  };

  /* ---------- SpeechRecognition ---------- */
  function SR() {
    this.lang = "en-US"; this.interimResults = false; this.continuous = false; this.maxAlternatives = 1;
    this.onstart = this.onend = this.onerror = this.onresult = this.onspeechstart = this.onspeechend =
      this.onaudiostart = this.onaudioend = this.onnomatch = null;
    this._id = 0; this._live = false;
  }
  SR.prototype.start = function () {
    if (this._live) throw new Error("InvalidStateError");
    this._id = ++rid; this._live = true; recs[this._id] = this; this._err = false;
    N.startRecognition(this._id, String(this.lang || "en-US"), !!this.interimResults);
  };
  SR.prototype.stop = function () { if (this._live) N.stopRecognition(this._id); };
  SR.prototype.abort = function () {
    if (!this._live) return;
    N.abortRecognition(this._id); var id = this._id; this._live = false; delete recs[id];
    this._call("onend", { type: "end" });
  };
  SR.prototype._call = function (name, ev) { var f = this[name]; if (typeof f === "function") { try { f.call(this, ev); } catch (e) { console.error(e); } } };
  SR.prototype._ev = function (type, d) {
    if (type === "start") { this._call("onaudiostart", { type: "audiostart" }); this._call("onstart", { type: "start" }); }
    else if (type === "speechstart") this._call("onspeechstart", { type: "speechstart" });
    else if (type === "speechend") { this._call("onspeechend", { type: "speechend" }); this._call("onaudioend", { type: "audioend" }); }
    else if (type === "result") {
      var alt = { transcript: d.t || "", confidence: d.c || 0 };
      var res = [alt]; res.isFinal = !!d["final"]; res.item = function (i) { return this[i]; };
      var list = [res]; list.item = function (i) { return this[i]; };
      this._call("onresult", { type: "result", resultIndex: 0, results: list });
    }
    else if (type === "error") { this._err = true; this._call("onerror", { type: "error", error: d.error || "network", message: d.error || "" }); }
    else if (type === "end") { this._live = false; delete recs[this._id]; this._call("onend", { type: "end" }); }
  };
  if (N.recognitionAvailable()) {
    window.SpeechRecognition = SR;
    window.webkitSpeechRecognition = SR;
  }

  /* ---------- speechSynthesis ---------- */
  function Utt(text) {
    this.text = String(text == null ? "" : text); this.lang = ""; this.voice = null; this.rate = 1; this.pitch = 1; this.volume = 1;
    this.onstart = this.onend = this.onerror = this.onpause = this.onresume = this.onboundary = null;
  }
  Utt.prototype._call = function (name, ev) { var f = this[name]; if (typeof f === "function") { try { f.call(this, ev); } catch (e) { console.error(e); } } };
  Utt.prototype._ev = function (type, d) {
    if (type === "end" || type === "error") {
      if (this._done) return; this._done = true; delete utts[this._id]; if (curU === this) curU = null;
      synth.speaking = false;
      this._call(type === "end" ? "onend" : "onerror", type === "end" ? { type: "end" } : { type: "error", error: d.error || "synthesis-failed" });
    }
  };
  var synth = {
    speaking: false, pending: false, paused: false, onvoiceschanged: null,
    getVoices: function () { return []; },
    speak: function (u) {
      u._id = ++uid; u._done = false; utts[u._id] = u; curU = u; synth.speaking = true;
      N.speak(u._id, u.text, u.lang || "en-US", Number(u.rate) || 1);
      setTimeout(function () { if (!u._done) u._call("onstart", { type: "start" }); }, 0);
    },
    cancel: function () {
      var u = curU; N.stopSpeaking(); synth.speaking = false;
      if (u && !u._done) u._ev("error", { error: "canceled" });
    },
    pause: function () {}, resume: function () {},
    addEventListener: function () {}, removeEventListener: function () {}
  };
  if (N.ttsAvailable()) {
    try { Object.defineProperty(window, "speechSynthesis", { value: synth, configurable: true, writable: true }); }
    catch (e) { window.speechSynthesis = synth; }
    window.SpeechSynthesisUtterance = Utt;
  }
})();
