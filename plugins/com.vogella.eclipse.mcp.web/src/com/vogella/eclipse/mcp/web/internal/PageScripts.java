package com.vogella.eclipse.mcp.web.internal;

import com.vogella.eclipse.mcp.core.json.Json;

/**
 * The JavaScript the web tools run in the page.
 * <p>
 * The snapshot keeps every element it listed in {@code window.__mcpRefs}, a map from ref to element, and gives an
 * element the same ref in every snapshot of a document through the weak map {@code window.__mcpIds}. Numbering
 * starts at a number {@link WebBrowser#reserveRefs} set aside for the snapshot before it ran, enough for every element
 * of the page and 200 more, so a ref is not reused for another element, not even after a navigation or a snapshot
 * that failed half way, unless the page grew by more than those 200 elements before that snapshot: an old ref either
 * still names its element or is refused. The page's DOM
 * is never changed.
 */
final class PageScripts {

	private PageScripts() {
	}

	private static final String SNAPSHOT = """
			(rootSelector, maxNodes, firstRef) => {
			  const refs = window.__mcpRefs || (window.__mcpRefs = new Map());
			  window.__mcpNext = Math.max(window.__mcpNext || 0, firstRef - 1);
			  for (const [ref, el] of refs) if (!el.isConnected) refs.delete(ref);
			  const ids = window.__mcpIds || (window.__mcpIds = new WeakMap());
			  const lines = [];
			  let total = 0;
			  const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
			  const cut = s => s.length > 120 ? s.slice(0, 117) + '...' : s;
			  const quote = s => JSON.stringify(cut(s));
			  const NAME_FROM_CONTENT = new Set(['button', 'link', 'heading', 'tab', 'menuitem', 'option', 'cell',
			      'columnheader', 'rowheader', 'checkbox', 'radio', 'switch', 'treeitem', 'listitem', 'label', 'summary']);
			  const LEAF = new Set(['button', 'link', 'textbox', 'searchbox', 'combobox', 'checkbox', 'radio', 'switch',
			      'slider', 'spinbutton', 'option', 'img', 'heading', 'menuitem', 'tab']);
			  const TAGS = {A: 'link', BUTTON: 'button', SELECT: 'combobox', TEXTAREA: 'textbox', OPTION: 'option',
			      IMG: 'img', NAV: 'navigation', MAIN: 'main', HEADER: 'banner', FOOTER: 'contentinfo', ASIDE: 'complementary',
			      FORM: 'form', TABLE: 'table', TR: 'row', TH: 'columnheader', TD: 'cell', UL: 'list', OL: 'list',
			      LI: 'listitem', DIALOG: 'dialog', SUMMARY: 'button', DETAILS: 'group', P: 'paragraph', LABEL: 'label',
			      H1: 'heading', H2: 'heading', H3: 'heading', H4: 'heading', H5: 'heading', H6: 'heading',
			      FIELDSET: 'group', LEGEND: 'legend', IFRAME: 'iframe', PROGRESS: 'progressbar', SECTION: 'region',
			      ARTICLE: 'article', HR: 'separator', CODE: 'code', PRE: 'code'};
			  const INPUTS = {button: 'button', submit: 'button', reset: 'button', image: 'button', checkbox: 'checkbox',
			      radio: 'radio', range: 'slider', number: 'spinbutton', search: 'searchbox', file: 'button', hidden: null};
			  const roleOf = el => {
			    const explicit = el.getAttribute('role');
			    if (explicit) return explicit.split(' ')[0];
			    if (el.tagName === 'A') return el.hasAttribute('href') ? 'link' : null;
			    if (el.tagName === 'INPUT') {
			      const type = (el.getAttribute('type') || 'text').toLowerCase();
			      return type in INPUTS ? INPUTS[type] : 'textbox';
			    }
			    if (el.tagName === 'SELECT' && (el.multiple || el.size > 1)) return 'listbox';
			    if (el.tagName === 'IMG' && el.getAttribute('alt') === '') return null;
			    if (el.isContentEditable && (!el.parentElement || !el.parentElement.isContentEditable)) return 'textbox';
			    return TAGS[el.tagName] || null;
			  };
			  const hidden = el => {
			    if (el.hidden || el.getAttribute('aria-hidden') === 'true') return true;
			    const style = getComputedStyle(el);
			    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return true;
			    // also what is not rendered for a reason of an ancestor, such as the content of a closed <details>
			    // or of content-visibility: hidden, which the element's own style does not show
			    return typeof el.checkVisibility === 'function' && !el.checkVisibility() && style.display !== 'contents';
			  };
			  const textOf = el => clean(el.innerText !== undefined ? el.innerText : el.textContent);
			  const nameOf = (el, role) => {
			    const label = el.getAttribute('aria-label');
			    if (label && clean(label)) return clean(label);
			    const by = el.getAttribute('aria-labelledby');
			    if (by) {
			      const text = by.split(/\\s+/).map(id => document.getElementById(id)).filter(Boolean)
			          .map(textOf).join(' ');
			      if (clean(text)) return clean(text);
			    }
			    if (el.labels && el.labels.length) {
			      const text = Array.from(el.labels).map(textOf).join(' ');
			      if (clean(text)) return clean(text);
			    }
			    if (el.tagName === 'IMG' || (el.tagName === 'INPUT' && el.type === 'image')) return clean(el.getAttribute('alt'));
			    if (el.tagName === 'INPUT' && ['button', 'submit', 'reset'].includes(el.type))
			      return clean(el.value) || (el.type === 'submit' ? 'Submit' : el.type === 'reset' ? 'Reset' : '');
			    if (NAME_FROM_CONTENT.has(role)) {
			      const text = textOf(el);
			      if (text) return text;
			    }
			    return clean(el.getAttribute('placeholder')) || clean(el.getAttribute('title'));
			  };
			  const describe = (el, role, depth) => {
			    let ref = ids.get(el);
			    if (!ref) {
			      window.__mcpNext++;
			      ref = 'e' + window.__mcpNext;
			      ids.set(el, ref);
			    }
			    refs.set(ref, el);
			    let line = '  '.repeat(depth) + '- ' + role;
			    const name = nameOf(el, role);
			    if (name) line += ' ' + quote(name);
			    if (role === 'heading') {
			      const level = el.getAttribute('aria-level') || (/^H[1-6]$/.test(el.tagName) ? el.tagName[1] : null);
			      if (level) line += ' [level=' + level + ']';
			    }
			    if (el.disabled || el.getAttribute('aria-disabled') === 'true') line += ' [disabled]';
			    if (el.type === 'checkbox' || el.type === 'radio') { if (el.checked) line += ' [checked]'; }
			    else if (el.getAttribute('aria-checked') === 'true') line += ' [checked]';
			    const expanded = el.getAttribute('aria-expanded');
			    if (expanded) line += ' [expanded=' + expanded + ']';
			    if (el.tagName === 'DETAILS') line += ' [expanded=' + el.open + ']';
			    if (el.selected || el.getAttribute('aria-selected') === 'true') line += ' [selected]';
			    if (el.required) line += ' [required]';
			    line += ' [ref=' + ref + ']';
			    if (role === 'link' && el.getAttribute('href')) line += ' -> ' + cut(el.getAttribute('href'));
			    if ((role === 'textbox' || role === 'searchbox' || role === 'spinbutton' || role === 'slider')) {
			      const value = el.isContentEditable ? textOf(el) : el.value;
			      if (value) line += ': ' + quote(el.type === 'password' ? '*'.repeat(value.length) : value);
			    }
			    if (role === 'combobox' && el.tagName === 'SELECT') {
			      const chosen = el.selectedOptions && el.selectedOptions[0];
			      if (chosen) line += ': ' + quote(clean(chosen.textContent));
			    }
			    return line;
			  };
			  const emit = line => { total++; if (lines.length < maxNodes) lines.push(line); };
			  const walk = (node, depth) => {
			    let text = '';
			    const flush = () => {
			      const t = clean(text);
			      if (t) emit('  '.repeat(depth) + '- text: ' + quote(t));
			      text = '';
			    };
			    const children = node.shadowRoot ? [...node.shadowRoot.childNodes, ...node.childNodes] : node.childNodes;
			    for (const child of children) {
			      if (child.nodeType === Node.TEXT_NODE) { text += ' ' + child.textContent; continue; }
			      if (child.nodeType !== Node.ELEMENT_NODE) continue;
			      if (['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'svg'].includes(child.tagName) || hidden(child)) continue;
			      if (child.tagName === 'BR') { text += ' '; continue; }
			      const role = roleOf(child);
			      if (role === 'presentation' || role === 'none' || role === null) {
			        const inline = getComputedStyle(child).display.startsWith('inline');
			        if (inline && !child.children.length && !child.shadowRoot) { text += ' ' + child.textContent; continue; }
			        flush();
			        walk(child, depth);
			        continue;
			      }
			      flush();
			      emit(describe(child, role, depth));
			      if (role === 'combobox' && child.tagName === 'SELECT') continue;
			      if (!LEAF.has(role) || child.querySelector('input,button,select,textarea,a[href]')) {
			        if (NAME_FROM_CONTENT.has(role)) walkElements(child, depth + 1);
			        else walk(child, depth + 1);
			      }
			    }
			    flush();
			  };
			  const walkElements = (node, depth) => {
			    for (const child of node.children) {
			      if (hidden(child)) continue;
			      const role = roleOf(child);
			      if (role && role !== 'presentation' && role !== 'none' && role !== 'label') {
			        emit(describe(child, role, depth));
			        if (!LEAF.has(role)) walkElements(child, depth + 1);
			      } else {
			        walkElements(child, depth);
			      }
			    }
			  };
			  const root = rootSelector ? document.querySelector(rootSelector) : document.body;
			  if (!root) throw new Error('No element matches ' + rootSelector);
			  if (root !== document.body && hidden(root)) throw new Error(rootSelector + ' is hidden, so a user sees nothing of it.');
			  walk(root, 0);
			  return JSON.stringify({snapshot: lines.join('\\n'), total, truncated: total > lines.length,
			      nextRef: window.__mcpNext + 1});
			}""";

	/**
	 * Finds the element a tool addresses, scrolls it into view and answers where its centre is and what is on top of
	 * it there, so a click lands on what the snapshot showed or is refused.
	 */
	private static final String LOCATE = """
			(ref, selector) => {
			  let el, matches = 1;
			  if (ref) {
			    el = window.__mcpRefs && window.__mcpRefs.get(ref);
			    if (!el) return JSON.stringify({error: 'No element ' + ref + ' in the last eclipse_web_snapshot of this page: it is gone, hidden, or the page navigated since. Take a new snapshot.'});
			    if (!el.isConnected) return JSON.stringify({error: 'Element ' + ref + ' was removed from the page since the snapshot; take a new snapshot.'});
			  } else {
			    let all;
			    try { all = document.querySelectorAll(selector); }
			    catch (e) { return JSON.stringify({error: JSON.stringify(selector) + ' is not a valid CSS selector.'}); }
			    if (!all.length) return JSON.stringify({error: 'No element matches ' + selector});
			    el = all[0];
			    matches = all.length;
			  }
			  el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});
			  const box = el.getBoundingClientRect();
			  const x = box.left + box.width / 2, y = box.top + box.height / 2;
			  // inside a shadow root the document only reports the host, which would read as covering its own content
			  const root = el.getRootNode();
			  const top = (root.elementFromPoint ? root : document).elementFromPoint(x, y);
			  const describe = e => e ? e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') +
			      (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\\s+/).join('.') : '') : null;
			  // an ancestor there covers it too: the target is not hit there (pointer-events: none, clipped), so the
			  // ancestor's handlers would run instead; only the target, its content and its labels take the click
			  const covered = top && top !== el && !el.contains(top) &&
			      !(el.labels && Array.from(el.labels).some(l => l.contains(top)));
			  window.__mcpTarget = el;
			  return JSON.stringify({x, y, width: box.width, height: box.height, scrollX: window.scrollX,
			      scrollY: window.scrollY, tag: el.tagName.toLowerCase(),
			      disabled: !!el.disabled || (el.matches && el.matches(':disabled')), coveredBy: covered ? describe(top) : null,
			      inert: !!(el.closest && el.closest('[inert]')),
			      // also a label for one, the usual styled upload button, since a click on it opens the chooser too
			      fileInput: (el.tagName === 'INPUT' && el.type === 'file') || (() => {
			        const label = el.tagName === 'LABEL' ? el : el.closest && el.closest('label');
			        const control = label && label.control;
			        return !!(control && control.tagName === 'INPUT' && control.type === 'file');
			      })(),
			      newTab: (() => {
			        const link = el.closest && el.closest('a[href]');
			        const target = link ? (link.getAttribute('target') || '').toLowerCase() : '';
			        return link && target && !['_self', '_parent', '_top'].includes(target) ? link.href : null;
			      })(),
			      matches});
			}""";

	/**
	 * Hides the fixed and sticky elements outside the located element, such as a site header, which would otherwise be
	 * painted over an element capture that reaches under them; answers how many it hid.
	 */
	static final String HIDE_OVERLAYS = """
			(() => {
			  const el = window.__mcpTarget, hidden = [];
			  for (const other of document.body.querySelectorAll('*')) {
			    if (other.contains(el) || el.contains(other)) continue;
			    const position = getComputedStyle(other).position;
			    if ((position === 'fixed' || position === 'sticky') && getComputedStyle(other).visibility !== 'hidden') {
			      hidden.push([other, other.style.getPropertyValue('visibility'), other.style.getPropertyPriority('visibility')]);
			      other.style.setProperty('visibility', 'hidden', 'important');
			    }
			  }
			  window.__mcpHidden = hidden;
			  return hidden.length;
			})()""";

	/**
	 * Waits until the running CSS animations and transitions that end by themselves have ended, such as a panel fading
	 * in after a click, or %d milliseconds passed; answers whether they ended. Infinite ones, such as a spinner, are
	 * not waited for.
	 */
	static final String FINISH_ANIMATIONS = """
			(() => {
			  const finite = document.getAnimations().filter(a => a.playState === 'running'
			      && Number.isFinite(a.effect?.getComputedTiming().endTime));
			  if (!finite.length) return true;
			  return Promise.race([Promise.all(finite.map(a => a.finished.catch(() => null))).then(() => true),
			      new Promise(done => setTimeout(() => done(false), %d))]);
			})()""";

	/** Puts back what {@link #HIDE_OVERLAYS} hid. */
	static final String RESTORE_OVERLAYS = """
			(() => {
			  for (const [el, value, priority] of window.__mcpHidden || []) {
			    if (value) el.style.setProperty('visibility', value, priority); else el.style.removeProperty('visibility');
			  }
			  window.__mcpHidden = null;
			})()""";

	/** Focuses the located element and, when asked, selects what it holds so the typed text replaces it. */
	static final String FOCUS_TARGET = """
			((clear) => {
			  const el = window.__mcpTarget;
			  // a shadow root has a selection of its own in Chromium, which the document's does not reach into
			  const selectionOf = e => { const r = e.getRootNode(); return r.getSelection ? r.getSelection() : window.getSelection(); };
			  if (el.tagName === 'SELECT') {
			    if (el.disabled || el.matches(':disabled')) return JSON.stringify({error: 'The select is disabled, so a user cannot pick an option in it.'});
			    return JSON.stringify({select: true});
			  }
			  el.focus();
			  const NOT_TEXT = ['button', 'submit', 'reset', 'image', 'checkbox', 'radio', 'file', 'range', 'color', 'hidden'];
			  if (el.tagName === 'INPUT' && NOT_TEXT.includes(el.type))
			    return JSON.stringify({error: 'The element is an input of type ' + el.type + ', which does not take typed text; use eclipse_web_click for it.'});
			  const editable = el.isContentEditable || el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';
			  if (!editable) return JSON.stringify({error: 'The element is a ' + el.tagName.toLowerCase() + ', which does not take text.'});
			  if (el.disabled || el.matches(':disabled')) return JSON.stringify({error: 'The field is disabled, so a user cannot type into it.'});
			  if (el.readOnly) return JSON.stringify({error: 'The field is read-only, so a user cannot type into it.'});
			  // inside a shadow root the document's active element is the host, so ask the root the field lives in
			  const active = el.getRootNode().activeElement;
			  if (active !== el && !(el.contains && el.contains(active)))
			    return JSON.stringify({error: 'The field did not take the focus, so typed text would go elsewhere.'});
			  if (clear) {
			    if (el.isContentEditable) { selectionOf(el).selectAllChildren(el); }
			    else if (el.selectionStart === null) {
			      // number, date and similar inputs have no text selection, and select() quietly does nothing for them;
			      // empty them as a user selecting all and deleting would
			      el.value = '';
			      el.dispatchEvent(new Event('input', {bubbles: true}));
			    } else {
			      el.select();
			    }
			  } else if (el.isContentEditable) {
			    // the caret after the content, so the text is appended
			    const range = document.createRange();
			    range.selectNodeContents(el);
			    range.collapse(false);
			    const selection = selectionOf(el);
			    selection.removeAllRanges();
			    selection.addRange(range);
			  } else if (el.selectionStart !== null) {
			    el.setSelectionRange(el.value.length, el.value.length);
			  } else if (el.value) {
			    // without a text selection the caret cannot be put after the value, so the text would land before it
			    return JSON.stringify({error: 'A ' + el.type + ' field cannot be appended to; pass clear true (the default) with the whole value.'});
			  }
			  return JSON.stringify({focused: el.getRootNode().activeElement === el, hadValue: !!(el.value || el.textContent)});
			})""";

	/** Chooses the option of the located select whose label or value is the text, as a user picking it would. */
	static final String CHOOSE_OPTION = """
			((text) => {
			  const el = window.__mcpTarget;
			  const wanted = text.trim();
			  if (!wanted) return JSON.stringify({error: 'Pass the label or value of the option to pick; a select cannot be emptied by typing.'});
			  let option = Array.from(el.options).find(o => o.label.trim() === wanted || o.value === wanted)
			      || Array.from(el.options).find(o => o.label.trim().toLowerCase() === wanted.toLowerCase());
			  if (!option) {
			    const partial = Array.from(el.options).filter(o => o.label.trim().toLowerCase().includes(wanted.toLowerCase()));
			    if (partial.length > 1) return JSON.stringify({error: JSON.stringify(wanted) + ' is part of several options, ' +
			        JSON.stringify(partial.map(o => o.label.trim())) + '; pass the whole label or the value of one.'});
			    option = partial[0];
			  }
			  if (!option) return JSON.stringify({error: 'No option ' + JSON.stringify(wanted) + '; the options are ' +
			      JSON.stringify(Array.from(el.options).map(o => o.label.trim()))});
			  el.focus();
			  el.value = option.value;
			  el.dispatchEvent(new Event('input', {bubbles: true}));
			  el.dispatchEvent(new Event('change', {bubbles: true}));
			  return JSON.stringify({chosen: option.label.trim(), value: option.value});
			})""";

	static String snapshot(String rootSelector, int maxNodes, long firstRef) {
		return call(SNAPSHOT, Json.write(rootSelector), String.valueOf(maxNodes), String.valueOf(firstRef));
	}

	static String locate(String ref, String selector) {
		return call(LOCATE, Json.write(ref), Json.write(selector));
	}

	static String call(String function, String... arguments) {
		return "(" + function + ")(" + String.join(", ", arguments) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
}
