// Chromium Developer Bridge page helpers. Evaluated in the page's main world on demand.
// Returns an object with snapshot/inspect/audit functions. Never returns values of sensitive fields.
(() => {
  if (window.__cdbHelpers && window.__cdbHelpers.version === 1) return window.__cdbHelpers;

  const refs = (() => {
    if (!window.__cdbRefs) {
      Object.defineProperty(window, '__cdbRefs', { value: new Map(), enumerable: false, configurable: true });
      Object.defineProperty(window, '__cdbRefIds', { value: new WeakMap(), enumerable: false, configurable: true });
      Object.defineProperty(window, '__cdbRefSeq', { value: { n: 0 }, enumerable: false, configurable: true });
    }
    return { byRef: window.__cdbRefs, byEl: window.__cdbRefIds, seq: window.__cdbRefSeq };
  })();

  function refFor(el) {
    let r = refs.byEl.get(el);
    if (!r) {
      r = 'element-' + (++refs.seq.n);
      refs.byEl.set(el, r);
      refs.byRef.set(r, new WeakRef(el));
    }
    return r;
  }

  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'META', 'LINK', 'HEAD', 'TITLE', 'BASE']);
  const LANDMARK = new Set(['banner', 'navigation', 'main', 'contentinfo', 'complementary', 'region', 'form', 'search',
    'dialog', 'alertdialog', 'alert', 'status', 'heading', 'img', 'table', 'list', 'listitem', 'row', 'cell',
    'columnheader', 'rowheader', 'tablist', 'tab', 'tabpanel', 'menu', 'menubar', 'tree', 'grid', 'figure', 'article']);
  const WIDGET = new Set(['button', 'link', 'textbox', 'searchbox', 'checkbox', 'radio', 'combobox', 'listbox', 'option',
    'menuitem', 'menuitemcheckbox', 'menuitemradio', 'slider', 'spinbutton', 'switch', 'tab', 'treeitem', 'gridcell']);
  const SENSITIVE_NAME = /pass|secret|token|otp|cvv|cvc|ssn|card.?num|iban|pin\b/i;
  const MAX_TEXT = 200;

  function implicitRole(el) {
    const tag = el.tagName;
    const type = (el.getAttribute('type') || '').toLowerCase();
    switch (tag) {
      case 'A': return el.hasAttribute('href') ? 'link' : null;
      case 'BUTTON': return 'button';
      case 'INPUT':
        if (['button', 'submit', 'reset', 'image'].includes(type)) return 'button';
        if (type === 'checkbox') return 'checkbox';
        if (type === 'radio') return 'radio';
        if (type === 'range') return 'slider';
        if (type === 'number') return 'spinbutton';
        if (type === 'search') return 'searchbox';
        if (type === 'hidden') return null;
        return el.hasAttribute('list') ? 'combobox' : 'textbox';
      case 'TEXTAREA': return 'textbox';
      case 'SELECT': return (el.multiple || el.size > 1) ? 'listbox' : 'combobox';
      case 'OPTION': return 'option';
      case 'IMG': return el.getAttribute('alt') === '' ? 'presentation' : 'img';
      case 'H1': case 'H2': case 'H3': case 'H4': case 'H5': case 'H6': return 'heading';
      case 'NAV': return 'navigation';
      case 'MAIN': return 'main';
      case 'HEADER': return el.closest('article,section,main,aside,nav') ? null : 'banner';
      case 'FOOTER': return el.closest('article,section,main,aside,nav') ? null : 'contentinfo';
      case 'ASIDE': return 'complementary';
      case 'FORM': return 'form';
      case 'SECTION': return (el.hasAttribute('aria-label') || el.hasAttribute('aria-labelledby')) ? 'region' : null;
      case 'UL': case 'OL': return 'list';
      case 'LI': return 'listitem';
      case 'TABLE': return 'table';
      case 'TR': return 'row';
      case 'TD': return 'cell';
      case 'TH': return 'columnheader';
      case 'DIALOG': return 'dialog';
      case 'PROGRESS': return 'progressbar';
      case 'SUMMARY': return 'button';
      case 'FIGURE': return 'figure';
      case 'ARTICLE': return 'article';
      case 'P': return 'paragraph';
      default: return null;
    }
  }

  function role(el) {
    const explicit = el.getAttribute('role');
    if (explicit) return explicit.trim().split(/\s+/)[0];
    return implicitRole(el);
  }

  function clean(s, max) {
    if (s == null) return null;
    const t = String(s).replace(/\s+/g, ' ').trim();
    if (!t) return null;
    return t.length > (max || MAX_TEXT) ? t.slice(0, max || MAX_TEXT) + '…' : t;
  }

  function isSensitive(el) {
    if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement)) return false;
    const type = (el.getAttribute('type') || '').toLowerCase();
    if (type === 'password' || type === 'hidden') return true;
    const ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (/password|cc-|one-time-code|card/.test(ac)) return true;
    if (el.hasAttribute('data-cdb-sensitive')) return true;
    return SENSITIVE_NAME.test(el.name || '') || SENSITIVE_NAME.test(el.id || '');
  }

  function labelText(el) {
    if (el.labels && el.labels.length) {
      return clean(Array.from(el.labels).map(l => l.innerText || l.textContent).join(' '), 100);
    }
    return null;
  }

  function accessibleName(el) {
    const aria = el.getAttribute('aria-label');
    if (aria && aria.trim()) return clean(aria, 100);
    const by = el.getAttribute('aria-labelledby');
    if (by) {
      const t = by.split(/\s+/).map(id => document.getElementById(id)).filter(Boolean)
        .map(n => n.innerText || n.textContent).join(' ');
      if (t.trim()) return clean(t, 100);
    }
    const tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
      const type = (el.getAttribute('type') || '').toLowerCase();
      if (['submit', 'button', 'reset'].includes(type)) return clean(el.value || type, 100);
      if (type === 'image') return clean(el.getAttribute('alt'), 100);
      return labelText(el) || clean(el.getAttribute('title'), 100) || clean(el.getAttribute('placeholder'), 100);
    }
    if (tag === 'IMG') return clean(el.getAttribute('alt'), 100) || clean(el.getAttribute('title'), 100);
    const r = role(el);
    if (r && (WIDGET.has(r) || r === 'heading' || tag === 'A' || tag === 'BUTTON' || r === 'cell' || r === 'columnheader')) {
      return clean(el.innerText || el.textContent, 100) || clean(el.getAttribute('title'), 100);
    }
    if (tag === 'FIELDSET') {
      const legend = el.querySelector('legend');
      return legend ? clean(legend.textContent, 100) : null;
    }
    return clean(el.getAttribute('title'), 100);
  }

  function ownText(el) {
    let t = '';
    for (const n of el.childNodes) if (n.nodeType === Node.TEXT_NODE) t += n.textContent;
    return clean(t);
  }

  function visible(el) {
    if (typeof el.checkVisibility === 'function' && !el.checkVisibility({ checkOpacity: true, visibilityProperty: true })) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function interactive(el, r) {
    const tag = el.tagName;
    if (tag === 'INPUT') return (el.getAttribute('type') || '').toLowerCase() !== 'hidden';
    if (tag === 'BUTTON' || tag === 'SELECT' || tag === 'TEXTAREA' || tag === 'SUMMARY') return true;
    if (tag === 'A' && el.hasAttribute('href')) return true;
    if (el.isContentEditable) return true;
    if (r && WIDGET.has(r)) return true;
    if (el.hasAttribute('onclick')) return true;
    const ti = el.getAttribute('tabindex');
    return ti !== null && Number(ti) >= 0;
  }

  function disabled(el) {
    return !!(el.disabled || el.getAttribute('aria-disabled') === 'true' || (el.closest && el.closest('fieldset:disabled')));
  }

  function checked(el) {
    if (el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio')) return el.checked;
    const a = el.getAttribute('aria-checked');
    if (a === 'true') return true;
    if (a === 'false') return false;
    return null;
  }

  function value(el) {
    if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement)) return null;
    const type = (el.getAttribute('type') || '').toLowerCase();
    if (['checkbox', 'radio', 'submit', 'button', 'reset', 'image', 'file'].includes(type)) return null;
    const v = el instanceof HTMLSelectElement ? Array.from(el.selectedOptions).map(o => o.label || o.value).join(', ') : el.value;
    if (!v) return null;
    return isSensitive(el) ? '[REDACTED]' : clean(v);
  }

  const COMPACT_ATTRS = ['id', 'name', 'type', 'href', 'placeholder', 'data-testid', 'data-test-id', 'for', 'aria-expanded',
    'aria-selected', 'aria-invalid', 'aria-required', 'required', 'disabled', 'readonly', 'alt', 'title', 'target', 'action', 'method'];

  function attributes(el, full) {
    const out = {};
    if (full) {
      for (const a of el.attributes) {
        const n = a.name.toLowerCase();
        if (n === 'value' || n.startsWith('on')) continue;
        out[a.name] = clean(a.value, 300) || '';
      }
    } else {
      for (const n of COMPACT_ATTRS) {
        if (el.hasAttribute(n)) out[n] = clean(el.getAttribute(n), 200) || '';
      }
      if (el.classList && el.classList.length) out['class'] = clean(el.className, 120);
    }
    return Object.keys(out).length ? out : null;
  }

  function bounds(el) {
    const r = el.getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) };
  }

  function children(node) {
    const kids = [];
    if (node.shadowRoot) kids.push(...node.shadowRoot.children);
    kids.push(...node.children);
    return kids;
  }

  function snapshot(args) {
    const mode = args.mode || 'COMPACT';
    const full = mode === 'FULL';
    const includeHidden = args.includeHidden != null ? args.includeHidden : full;
    const max = args.maxElements || 500;
    const roleFilter = args.role ? args.role.toLowerCase() : null;
    const textFilter = args.text ? args.text.toLowerCase() : null;
    const root = args.root ? document.querySelector(args.root) : (document.body || document.documentElement);
    if (!root) throw new Error('Root element not found: ' + args.root);
    const out = [];
    let truncated = false;
    let total = 0;

    function include(el, r, isVisible, isInteractive) {
      if (!includeHidden && !isVisible) return false;
      if (mode === 'INTERACTIVE') return isInteractive;
      if (full) return true;
      return isInteractive || (r && LANDMARK.has(r)) || !!ownText(el) || el.tagName === 'LABEL' || el.tagName === 'IFRAME';
    }

    function walk(el, parentRef, depth) {
      if (SKIP.has(el.tagName)) return;
      const r = role(el);
      const isVisible = visible(el);
      if (!includeHidden && !isVisible && !full) {
        // Hidden subtree: children of display:none are hidden too.
        const cs = getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden') return;
      }
      const isInteractive = interactive(el, r);
      let myRef = parentRef;
      let myDepth = depth;
      if (include(el, r, isVisible, isInteractive)) {
        const name = accessibleName(el);
        const text = (mode === 'INTERACTIVE') ? null : ownText(el);
        const matches = (!roleFilter || (r || '').toLowerCase() === roleFilter)
          && (!textFilter || ((name || '') + ' ' + (text || '')).toLowerCase().includes(textFilter));
        total++;
        if (matches) {
          if (out.length >= max) {
            truncated = true;
          } else {
            const ref = refFor(el);
            out.push({
              reference: ref, parent: parentRef, depth: depth, tag: el.tagName.toLowerCase(), role: r, name: name,
              text: text && text !== name ? text : null, visible: isVisible, interactive: isInteractive,
              disabled: disabled(el), checked: checked(el), value: value(el),
              attributes: (mode === 'INTERACTIVE') ? attributes(el, false) : attributes(el, full), bounds: bounds(el)
            });
            myRef = ref;
            myDepth = depth + 1;
          }
        }
      }
      if (truncated && out.length >= max) return;
      for (const c of children(el)) walk(c, myRef, myDepth);
    }

    walk(root, null, 0);
    return { title: document.title, url: location.href, elements: out, truncated: truncated, total: total };
  }

  function computed(el, props) {
    const cs = getComputedStyle(el);
    const list = props && props.length ? props : ['display', 'visibility', 'opacity', 'position', 'z-index', 'color',
      'background-color', 'font-family', 'font-size', 'font-weight', 'line-height', 'width', 'height', 'margin', 'padding',
      'border', 'overflow', 'cursor', 'pointer-events', 'flex-direction', 'justify-content', 'align-items', 'grid-template-columns'];
    const out = {};
    for (const p of list.slice(0, 60)) out[p] = cs.getPropertyValue(p);
    return out;
  }

  function describe(el) {
    let s = el.tagName.toLowerCase();
    if (el.id) s += '#' + el.id;
    if (el.classList && el.classList.length) s += '.' + Array.from(el.classList).slice(0, 3).join('.');
    return s;
  }

  function inspect(el, props) {
    const r = role(el);
    const ancestors = [];
    for (let p = el.parentElement; p && ancestors.length < 12; p = p.parentElement) ancestors.push(describe(p));
    let formField = null;
    if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement) {
      formField = {
        type: el instanceof HTMLSelectElement ? 'select' : (el.getAttribute('type') || el.tagName.toLowerCase()),
        name: el.name || null, label: labelText(el), required: !!el.required, placeholder: el.getAttribute('placeholder'),
        value: value(el), sensitive: isSensitive(el), valid: el.validity ? el.validity.valid : true,
        validationMessage: el.validationMessage || null
      };
    }
    const editable = el.isContentEditable || ((el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) && !el.readOnly && !el.disabled);
    return {
      reference: refFor(el), tag: el.tagName.toLowerCase(), role: r, name: accessibleName(el),
      text: clean(el.innerText || el.textContent, 500), visible: visible(el), enabled: !disabled(el), editable: editable,
      checked: checked(el), attributes: attributes(el, true) || {}, computedStyles: computed(el, props), bounds: bounds(el),
      formField: formField, ancestors: ancestors, childCount: el.children.length
    };
  }

  function audit() {
    const issues = [];
    const add = (rule, severity, message, el) => issues.length < 200 && issues.push({ rule, severity, message, reference: el ? refFor(el) : null });
    if (!document.documentElement.getAttribute('lang')) add('html-lang', 'serious', '<html> has no lang attribute', null);
    if (!document.title) add('document-title', 'serious', 'Document has no title', null);
    for (const img of document.querySelectorAll('img')) {
      if (!img.hasAttribute('alt') && visible(img)) add('image-alt', 'critical', 'Image without alt text: ' + clean(img.src, 80), img);
    }
    for (const el of document.querySelectorAll('input, select, textarea')) {
      const type = (el.getAttribute('type') || '').toLowerCase();
      if (['hidden', 'submit', 'button', 'reset', 'image'].includes(type)) continue;
      if (!accessibleName(el) && visible(el)) add('label', 'critical', 'Form field without accessible name: ' + describe(el), el);
    }
    for (const el of document.querySelectorAll('button, [role=button], a[href]')) {
      if (!accessibleName(el) && visible(el)) add(el.tagName === 'A' ? 'link-name' : 'button-name', 'serious',
        'Control without accessible name: ' + describe(el), el);
    }
    let last = 0;
    for (const h of document.querySelectorAll('h1, h2, h3, h4, h5, h6')) {
      const level = Number(h.tagName[1]);
      if (last && level > last + 1) add('heading-order', 'moderate', 'Heading level skipped: h' + last + ' to h' + level, h);
      last = level;
    }
    const ids = new Map();
    for (const el of document.querySelectorAll('[id]')) ids.set(el.id, (ids.get(el.id) || 0) + 1);
    for (const [id, n] of ids) if (n > 1) add('duplicate-id', 'minor', 'Duplicate id "' + id + '" (' + n + ' elements)', document.getElementById(id));
    if (!document.querySelector('main, [role=main]')) add('landmark-main', 'moderate', 'Page has no main landmark', null);
    return issues;
  }

  const helpers = { version: 1, snapshot, inspect, audit };
  Object.defineProperty(window, '__cdbHelpers', { value: helpers, enumerable: false, configurable: true });
  return helpers;
})()
