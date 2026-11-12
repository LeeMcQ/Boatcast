/* Draws one slide into a box; shared by the phone page and the presenter's preview. */
(function (g) {
  'use strict';

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  /** Largest font size (px) at which the text box fits inside its parent. */
  function fit(box, parent, max) {
    var lo = 10, hi = max;
    while (hi - lo > 1) {
      var mid = (lo + hi) >> 1;
      box.style.fontSize = mid + 'px';
      if (box.scrollHeight <= parent.clientHeight && box.scrollWidth <= parent.clientWidth) lo = mid; else hi = mid;
    }
    box.style.fontSize = lo + 'px';
  }

  /**
   * view: {slide: {title, text, img}|null, url: image URL or null, blank, live: text line|null,
   *        waiting: message when there is no slide yet}
   */
  function render(root, view) {
    root.textContent = '';
    root.classList.toggle('blank', !!view.blank);
    root.classList.toggle('has-live', !!view.live && !view.blank);
    if (view.blank) return;
    var s = view.slide;
    if (!s) {
      root.appendChild(el('div', 'wait', view.waiting || 'Waiting for the presenter…'));
    } else if (s.img) {
      if (view.url) {
        var im = el('img', 'pic');
        im.alt = s.title || 'Slide';
        im.src = view.url;
        root.appendChild(im);
      } else {
        root.appendChild(el('div', 'wait', 'Loading picture…'));
      }
    } else {
      var area = el('div', 'area'), box = el('div', 'txt');
      if (s.title) box.appendChild(el('div', 'ttl', s.title));
      if (s.text) box.appendChild(el('div', 'body', s.text));
      area.appendChild(box);
      root.appendChild(area);
      fit(box, area, Math.max(16, Math.round(Math.min(area.clientHeight / 4, area.clientWidth / 5))));
    }
    if (view.live) root.appendChild(el('div', 'live', view.live));
  }

  /**
   * Slides from typed or pasted text: a blank line starts a new slide;
   * a first line starting with "# " is the slide's title.
   */
  function fromText(text) {
    return String(text || '').replace(/\r/g, '').split(/\n\s*\n/).map(function (b) {
      var lines = b.replace(/^\n+|\n+$/g, '').split('\n'), title = '';
      if (/^#\s/.test(lines[0] || '')) title = lines.shift().replace(/^#\s*/, '');
      return { title: title, text: lines.join('\n').trim(), img: null };
    }).filter(function (s) { return s.title || s.text; });
  }

  g.BoatShow = { render: render, fromText: fromText };
})(window);
