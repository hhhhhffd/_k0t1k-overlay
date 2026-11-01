// The overlay's own small versions of a few uBlock Origin scriptlets, named and called the same way
// as in the filter lists (host##+js(name, args...)). Runs as P.scriptlet(name, args) in the page.
P.scriptlet = function (name, args) {
  name = String(name).replace(/\.js$/, '');
  var table = {
    'json-prune': jsonPrune,
    // The fetch and XHR variants prune every JSON the page reads, not only those of one address.
    'json-prune-fetch-response': jsonPrune, 'json-prune-xhr-response': jsonPrune,
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
  function jsonPrune(a) {
    var prune = paths(a[0]), needed = paths(a[1]);
    function clean(value) {
      if (!value || typeof value !== 'object') return value;
      var ok = needed.every(function (n) { var found = false; each(value, n, 0, function () { found = true; }); return found; });
      if (ok) prune.forEach(function (p) { each(value, p, 0, function (o, k) { delete o[k]; }); });
      return value;
    }
    var parse = JSON.parse;
    JSON.parse = function () { return clean(parse.apply(this, arguments)); };
    if (window.Response && Response.prototype.json) {
      var json = Response.prototype.json;
      Response.prototype.json = function () { return json.apply(this, arguments).then(clean); };
    }
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
