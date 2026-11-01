// The overlay's own small versions of a few uBlock Origin scriptlets, named and called the same way
// as in the filter lists (host##+js(name, args...)). Runs as P.scriptlet(name, args) in the page.
P.scriptlet = function (name, args) {
  name = String(name).replace(/\.js$/, '');
  var table = {
    'json-prune': jsonPrune,
    'json-prune-fetch-response': jsonPruneFetch, 'json-prune-xhr-response': jsonPruneXhr,
    'set-constant': setConstant, 'set': setConstant,
    'abort-on-property-read': function (a) { trap(a[0], true); }, 'aopr': function (a) { trap(a[0], true); },
    'abort-on-property-write': function (a) { trap(a[0], false); }, 'aopw': function (a) { trap(a[0], false); }
  };
  var fn = table[name];
  if (!fn) return false;
  try { fn(args || []); } catch (e) {}
  return true;

  function paths(text) { return (text || '').split(/\s+/).filter(Boolean).map(function (p) { return p.split('.'); }); }
  function each(obj, segs, i, visit) {
    if (!obj || typeof obj !== 'object') return;
    var key = segs[i], last = i === segs.length - 1;
    if (key === '[-]' || key === '[]' || key === '*') {
      Object.keys(obj).forEach(function (k) { last ? visit(obj, k) : each(obj[k], segs, i + 1, visit); });
    } else if (last) {
      if (key in obj) visit(obj, key);
    } else {
      each(obj[key], segs, i + 1, visit);
    }
  }
  // Returns a function that prunes a parsed value in place and says whether it changed anything.
  function pruner(a) {
    var prune = paths(a[0]), needed = paths(a[1]);
    return function (value) {
      if (!value || typeof value !== 'object') return false;
      var ok = needed.every(function (n) { var found = false; each(value, n, 0, function () { found = true; }); return found; });
      var changed = false;
      if (ok) prune.forEach(function (p) { each(value, p, 0, function (o, k) { delete o[k]; changed = true; }); });
      return changed;
    };
  }
  // JSON.parse wrapper. The stack-matching third argument is not supported, so such rules are skipped.
  function jsonPrune(a) {
    if (a[2]) return;
    var clean = pruner(a), parse = JSON.parse;
    JSON.parse = function () { var value = parse.apply(this, arguments); clean(value); return value; };
  }
  // The request filter of the fetch/XHR variants: (rawPrune, rawNeedle[, 'propsToMatch', 'url:pattern']).
  // Returns a function of the request address, or null when the rule uses anything else.
  function requestMatcher(extra) {
    if (!extra.length) return function () { return true; };
    if (extra.length !== 2 || extra[0] !== 'propsToMatch') return null;
    var tests = [];
    var tokens = String(extra[1]).split(/\s+/).filter(Boolean);
    for (var i = 0; i < tokens.length; i++) {
      var m = /^url:(.*)$/.exec(tokens[i]);
      var pattern = m ? m[1] : tokens[i];
      if (!m && /^[a-z]+:[^/]/.test(pattern)) return null; // another property, such as method:
      var re = /^\/(.+)\/([gimsu]*)$/.exec(pattern);
      if (re) {
        var rx = new RegExp(re[1], re[2].replace('g', ''));
        tests.push(function (url) { return rx.test(url); });
      } else {
        tests.push((function (text) { return function (url) { return url.indexOf(text) !== -1; }; })(pattern));
      }
    }
    return function (url) { return tests.every(function (t) { return t(url); }); };
  }
  function absolute(url) { return new URL(String(url), location.href).href; }
  function jsonPruneFetch(a) {
    var matches = requestMatcher(a.slice(2));
    if (!matches || !window.fetch || !window.Response) return;
    var clean = pruner(a), parse = JSON.parse, fetch = window.fetch;
    window.fetch = function (input) {
      var result = fetch.apply(this, arguments), url;
      try { url = absolute(input && input.url || input); } catch (e) { return result; }
      if (!matches(url)) return result;
      return result.then(function (response) {
        return response.clone().text().then(function (text) {
          var value = parse(text);
          if (!clean(value)) return response;
          return new Response(JSON.stringify(value), { status: response.status, statusText: response.statusText, headers: response.headers });
        }).catch(function () { return response; });
      });
    };
  }
  function jsonPruneXhr(a) {
    var matches = requestMatcher(a.slice(2));
    if (!matches || !window.XMLHttpRequest) return;
    var clean = pruner(a), parse = JSON.parse, proto = XMLHttpRequest.prototype, open = proto.open;
    var text = Object.getOwnPropertyDescriptor(proto, 'responseText'), body = Object.getOwnPropertyDescriptor(proto, 'response');
    if (!text || !body) return;
    proto.open = function (method, url) {
      var xhr = this, hit = false;
      try { hit = matches(absolute(url)); } catch (e) {}
      if (hit) {
        var seen = null, pruned = null;
        var prunedText = function (raw) {
          if (typeof raw !== 'string' || !raw) return raw;
          if (raw === seen) return pruned;
          seen = raw; pruned = raw;
          try { var value = parse(raw); if (clean(value)) pruned = JSON.stringify(value); } catch (e) {}
          return pruned;
        };
        Object.defineProperty(xhr, 'responseText', { configurable: true, get: function () { return prunedText(text.get.call(xhr)); } });
        Object.defineProperty(xhr, 'response', {
          configurable: true,
          get: function () {
            var raw = body.get.call(xhr);
            if (xhr.responseType === 'json') { clean(raw); return raw; }
            return xhr.responseType === '' || xhr.responseType === 'text' ? prunedText(raw) : raw;
          }
        });
      }
      return open.apply(this, arguments);
    };
  }
  function constant(text) {
    switch (text) {
      case 'undefined': return undefined;
      case 'false': return false;
      case 'true': return true;
      case 'null': return null;
      case 'noopFunc': return function () {};
      case 'trueFunc': return function () { return true; };
      case 'falseFunc': return function () { return false; };
      case 'emptyStr': case "''": case '': return '';
      case 'emptyObj': return {};
      case 'emptyArr': return [];
      default: return /^-?\d+(\.\d+)?$/.test(text) ? Number(text) : text;
    }
  }
  function setConstant(a) {
    define(window, String(a[0]).split('.'), constant(a[1]));
    function define(root, chain, value) {
      var key = chain[0];
      if (chain.length === 1) {
        Object.defineProperty(root, key, { get: function () { return value; }, set: function () {}, configurable: true });
        return;
      }
      var current = root[key];
      if (current && typeof current === 'object') { define(current, chain.slice(1), value); return; }
      Object.defineProperty(root, key, {
        configurable: true,
        get: function () { return current; },
        set: function (v) { current = v; if (v && typeof v === 'object') define(v, chain.slice(1), value); }
      });
    }
  }
  function trap(path, onRead) {
    var chain = String(path).split('.'), key = chain.pop(), root = window;
    for (var i = 0; i < chain.length; i++) { root = root[chain[i]]; if (!root) return; }
    var value = root[key];
    Object.defineProperty(root, key, {
      configurable: true,
      get: function () { if (onRead) throw new ReferenceError(key); return value; },
      set: function (v) { if (!onRead) throw new ReferenceError(key); value = v; }
    });
  }
};
