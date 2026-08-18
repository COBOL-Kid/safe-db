package com.safedb.export

// Invariant styling/behavior for the exported report. All data-dependent content is embedded as
// JSON and rendered by REPORT_JS via createElement/textContent, never innerHTML.
internal val REPORT_CSS =
    """
    :root {
      --bg: #f7f8fa; --surface: #ffffff; --surface-alt: #eef0f4; --text: #1c1f26;
      --text-muted: #5c6370; --border: #d8dce3; --accent: #2f6fed; --accent-soft: #e3ecfd;
      --warn-bg: #fdf3d7; --warn-text: #6b5416; --error: #b3261e;
      --s0: #2f6fed; --s1: #e8863a; --s2: #2e9e6b; --s3: #d05574;
      --s4: #8a63d2; --s5: #3aa5b9; --s6: #c2a032; --s7: #7a7f8a;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #14161b; --surface: #1d2026; --surface-alt: #262a32; --text: #e6e8ec;
        --text-muted: #9aa1ad; --border: #363b45; --accent: #6f9cf5; --accent-soft: #253654;
        --warn-bg: #4a3d14; --warn-text: #ecd992; --error: #f2b8b5;
      }
    }
    * { box-sizing: border-box; }
    body {
      margin: 0; padding: 24px; background: var(--bg); color: var(--text);
      font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
    }
    header { margin-bottom: 20px; }
    header h1 { margin: 0 0 4px; font-size: 22px; }
    .meta { color: var(--text-muted); font-size: 13px; }
    .badge {
      display: inline-block; padding: 1px 8px; margin-left: 6px; border-radius: 10px;
      background: var(--warn-bg); color: var(--warn-text); font-size: 12px;
    }
    .warning {
      margin: 8px 0; padding: 8px 12px; border-radius: 6px;
      background: var(--warn-bg); color: var(--warn-text); font-size: 13px;
    }
    section { margin-bottom: 28px; }
    .section-title { font-size: 16px; font-weight: 600; margin: 0 0 10px; }
    .filter {
      margin-bottom: 10px; padding: 6px 10px; width: 280px; max-width: 100%;
      border: 1px solid var(--border); border-radius: 6px;
      background: var(--surface); color: var(--text); font: inherit;
    }
    .scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; }
    th, td { padding: 6px 12px; text-align: left; white-space: nowrap; border-bottom: 1px solid var(--border); }
    td { max-width: 380px; overflow: hidden; text-overflow: ellipsis; }
    thead th { position: sticky; top: 0; background: var(--surface-alt); font-weight: 600; z-index: 2; }
    th.sortable { cursor: pointer; user-select: none; }
    th.sortable:hover { color: var(--accent); }
    th .arrow { font-size: 10px; margin-left: 4px; }
    td.num, th.num { text-align: right; }
    th.total, td.total-col { background: var(--accent-soft); }
    tr.group td, tr.total td, tr.subtotal td, tr.grandtotal td { font-weight: 600; }
    tr.grandtotal td, tr.total td { background: var(--accent-soft); }
    tr.group td, tr.subtotal td { background: var(--surface-alt); }
    .sticky-col { position: sticky; left: 0; background: var(--surface); z-index: 1; }
    thead .sticky-col { background: var(--surface-alt); z-index: 3; }
    tr.group .sticky-col, tr.subtotal .sticky-col { background: var(--surface-alt); }
    tr.grandtotal .sticky-col, tr.total .sticky-col { background: var(--accent-soft); }
    td.drill, tr.drill-row td { cursor: pointer; }
    td.drill:hover, tr.drill-row:hover td { background: var(--accent-soft); }
    .hint { color: var(--text-muted); font-size: 12px; margin: 6px 0 0; }
    .empty { color: var(--text-muted); padding: 16px; }
    svg.chart { width: 100%; height: auto; display: block; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; }
    svg.chart text { font: 12px ui-monospace, "SF Mono", Consolas, monospace; fill: var(--text-muted); }
    svg .grid { stroke: var(--border); stroke-width: 1; }
    svg .axis { stroke: var(--text-muted); stroke-width: 1.5; }
    svg .shape { transition: opacity 0.1s; }
    svg .shape.drill { cursor: pointer; }
    svg .shape:hover { opacity: 0.75; }
    .fill-s0 { fill: var(--s0); } .fill-s1 { fill: var(--s1); } .fill-s2 { fill: var(--s2); } .fill-s3 { fill: var(--s3); }
    .fill-s4 { fill: var(--s4); } .fill-s5 { fill: var(--s5); } .fill-s6 { fill: var(--s6); } .fill-s7 { fill: var(--s7); }
    .stroke-s0 { stroke: var(--s0); } .stroke-s1 { stroke: var(--s1); } .stroke-s2 { stroke: var(--s2); } .stroke-s3 { stroke: var(--s3); }
    .stroke-s4 { stroke: var(--s4); } .stroke-s5 { stroke: var(--s5); } .stroke-s6 { stroke: var(--s6); } .stroke-s7 { stroke: var(--s7); }
    .legend { display: flex; flex-wrap: wrap; gap: 6px 16px; margin: 10px 0; font-size: 13px; }
    .legend .chip { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 5px; }
    .kpi {
      display: inline-flex; flex-direction: column; align-items: center; gap: 4px;
      padding: 30px 48px; border-radius: 8px; background: var(--accent-soft);
    }
    .kpi.drill { cursor: pointer; }
    .kpi .value { font-size: 32px; font-weight: 600; }
    .kpi .sub { color: var(--text-muted); font-size: 12px; }
    dialog {
      border: 1px solid var(--border); border-radius: 10px; padding: 18px;
      background: var(--surface); color: var(--text); max-width: 90vw; width: 900px;
    }
    dialog::backdrop { background: rgba(0, 0, 0, 0.45); }
    dialog h2 { margin: 0 0 10px; font-size: 16px; }
    dialog .scroll { max-height: 60vh; overflow-y: auto; }
    dialog .close { float: right; border: 1px solid var(--border); border-radius: 6px; background: var(--surface-alt); color: var(--text); padding: 4px 12px; cursor: pointer; font: inherit; }
    @media print {
      body { background: #ffffff; color: #1c1f26; padding: 0; }
      .filter, dialog, .hint { display: none; }
      .scroll { overflow: visible; border: none; }
      thead th { position: static; }
      .sticky-col { position: static; }
    }
    """
        .trimIndent()

internal val REPORT_JS =
    """
    'use strict';
    const REPORT = JSON.parse(document.getElementById('report-data').textContent);
    const app = document.getElementById('app');
    const dialog = document.getElementById('drill');
    const SERIES_COUNT = 8;

    function el(tag, cls, text) {
      const node = document.createElement(tag);
      if (cls) node.className = cls;
      if (text !== undefined) node.textContent = text;
      return node;
    }

    function openDrill(indices) {
      const source = REPORT.source;
      if (!source || !indices || !indices.length) return;
      dialog.textContent = '';
      const close = el('button', 'close', 'Close');
      close.addEventListener('click', () => dialog.close());
      dialog.appendChild(close);
      dialog.appendChild(el('h2', null, indices.length + ' source row' + (indices.length === 1 ? '' : 's')));
      const rows = indices
        .filter((i) => i >= 0 && i < source.rows.length)
        .map((i) => ({ cells: source.rows[i].map((t) => ({ t })), kind: 'detail', depth: 0 }));
      const columns = source.columns.map((label) => ({ label, numeric: false }));
      dialog.appendChild(buildTableWidget({ columns, rows, sortable: true }, null));
      dialog.showModal();
    }

    function cellText(cell) { return cell ? cell.t : ''; }

    function buildTableWidget(section, labelColumn) {
      const wrap = el('div');
      const filter = el('input', 'filter');
      filter.type = 'search';
      filter.placeholder = 'Filter rows…';
      wrap.appendChild(filter);
      const scroll = el('div', 'scroll');
      wrap.appendChild(scroll);
      if (!section.rows.length) {
        scroll.appendChild(el('div', 'empty', 'No rows.'));
        filter.hidden = true;
        return wrap;
      }

      let sortIndex = null;
      let sortDir = 1;

      function render() {
        scroll.textContent = '';
        const table = el('table');
        const thead = el('thead');
        const headRow = el('tr');
        section.columns.forEach((column, index) => {
          const th = el('th', column.numeric ? 'num' : null, column.label);
          if (index === 0 && labelColumn) th.classList.add('sticky-col');
          if (section.sortable) {
            th.classList.add('sortable');
            if (sortIndex === index) th.appendChild(el('span', 'arrow', sortDir > 0 ? '▲' : '▼'));
            th.addEventListener('click', () => {
              if (sortIndex === index) {
                if (sortDir > 0) { sortDir = -1; } else { sortIndex = null; sortDir = 1; }
              } else { sortIndex = index; sortDir = 1; }
              render();
            });
          }
          headRow.appendChild(th);
        });
        thead.appendChild(headRow);
        table.appendChild(thead);

        const needle = filter.value.trim().toLowerCase();
        let rows = section.rows.filter((row) =>
          row.kind === 'total' || row.kind === 'grandtotal' || !needle ||
          row.cells.some((cell) => cellText(cell).toLowerCase().includes(needle)));
        if (sortIndex !== null) {
          const numeric = section.columns[sortIndex].numeric;
          rows = rows.slice().sort((a, b) => {
            const ca = a.cells[sortIndex] || {};
            const cb = b.cells[sortIndex] || {};
            const cmp = numeric
              ? (ca.n === undefined ? -Infinity : ca.n) - (cb.n === undefined ? -Infinity : cb.n)
              : cellText(ca).localeCompare(cellText(cb));
            return cmp * sortDir;
          });
        }

        const tbody = el('tbody');
        rows.forEach((row) => {
          const tr = el('tr', row.kind !== 'detail' ? row.kind : null);
          if (row.d) {
            tr.classList.add('drill-row');
            tr.title = 'Click to view the source row';
            tr.addEventListener('click', () => openDrill(row.d));
          }
          row.cells.forEach((cell, index) => {
            const td = el('td', section.columns[index] && section.columns[index].numeric ? 'num' : null, cellText(cell));
            if (index === 0 && labelColumn) {
              td.classList.add('sticky-col');
              td.style.paddingLeft = 12 + (row.depth || 0) * 16 + 'px';
            }
            tr.appendChild(td);
          });
          tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        scroll.appendChild(table);
      }

      filter.addEventListener('input', render);
      render();
      return wrap;
    }

    function renderPivot(pivot) {
      const section = el('section');
      section.appendChild(el('h2', 'section-title', 'Pivot'));
      if (pivot.overflowMessage) section.appendChild(el('div', 'warning', pivot.overflowMessage));
      const filter = el('input', 'filter');
      filter.type = 'search';
      filter.placeholder = 'Filter rows…';
      section.appendChild(filter);
      const scroll = el('div', 'scroll');
      section.appendChild(scroll);
      const valueColumns = pivot.leafHeaders.length;

      function headerRowEl(cells, leafRow) {
        const tr = el('tr');
        if (pivot.hasRowLabels) {
          const th = el('th', 'sticky-col', leafRow ? 'Row labels' : '');
          tr.appendChild(th);
        }
        let covered = 0;
        cells.forEach((cell) => {
          if (cell.start > covered) {
            const gap = el('th');
            gap.colSpan = cell.start - covered;
            tr.appendChild(gap);
          }
          const th = el('th', cell.isTotal ? 'total' : null, cell.label);
          if (leafRow) th.classList.add('num');
          th.colSpan = cell.span;
          tr.appendChild(th);
          covered = cell.start + cell.span;
        });
        if (covered < valueColumns) {
          const gap = el('th');
          gap.colSpan = valueColumns - covered;
          tr.appendChild(gap);
        }
        return tr;
      }

      function render() {
        scroll.textContent = '';
        const table = el('table');
        const thead = el('thead');
        pivot.headerRows.forEach((cells) => thead.appendChild(headerRowEl(cells, false)));
        thead.appendChild(headerRowEl(pivot.leafHeaders, true));
        table.appendChild(thead);

        const needle = filter.value.trim().toLowerCase();
        const tbody = el('tbody');
        pivot.rows.forEach((row) => {
          const keep = row.kind === 'subtotal' || row.kind === 'grandtotal' || !needle ||
            (row.label || '').toLowerCase().includes(needle) ||
            row.cells.some((cell) => cell.t.toLowerCase().includes(needle));
          if (!keep) return;
          const tr = el('tr', row.kind !== 'leaf' ? row.kind : null);
          if (pivot.hasRowLabels) {
            const td = el('td', 'sticky-col', row.label || '');
            td.style.paddingLeft = 12 + (row.depth || 0) * 16 + 'px';
            tr.appendChild(td);
          }
          row.cells.forEach((cell, index) => {
            const td = el('td', 'num', cell.t);
            if (pivot.leafHeaders[index] && pivot.leafHeaders[index].isTotal) td.classList.add('total-col');
            if (cell.d) {
              td.classList.add('drill');
              td.title = 'Click to view ' + cell.d.length + ' source row' + (cell.d.length === 1 ? '' : 's');
              td.addEventListener('click', () => openDrill(cell.d));
            }
            tr.appendChild(td);
          });
          tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        scroll.appendChild(table);
      }

      filter.addEventListener('input', render);
      render();
      section.appendChild(el('p', 'hint', 'Click a value to see its source rows.'));
      app.appendChild(section);
    }

    const SVG_NS = 'http://www.w3.org/2000/svg';

    function svgEl(tag, attrs, cls) {
      const node = document.createElementNS(SVG_NS, tag);
      for (const key in attrs) node.setAttribute(key, attrs[key]);
      if (cls) node.setAttribute('class', cls);
      return node;
    }

    function renderChart(chart) {
      const section = el('section');
      section.appendChild(el('h2', 'section-title', chart.title || 'Chart'));

      if (chart.kpis.length) {
        chart.kpis.forEach((kpi) => {
          const tile = el('div', 'kpi');
          tile.appendChild(el('div', 'value', kpi.value));
          tile.appendChild(el('div', null, kpi.label));
          tile.appendChild(el('div', 'sub', kpi.sublabel));
          if (kpi.d) {
            tile.classList.add('drill');
            tile.addEventListener('click', () => openDrill(kpi.d));
          }
          section.appendChild(tile);
        });
        app.appendChild(section);
        return;
      }

      if (chart.legend.length) {
        const legend = el('div', 'legend');
        chart.legend.forEach((label, index) => {
          const item = el('span');
          const chip = el('span', 'chip');
          chip.style.background = 'var(--s' + (index % SERIES_COUNT) + ')';
          item.appendChild(chip);
          item.appendChild(document.createTextNode(label));
          legend.appendChild(item);
        });
        if (chart.legendMore > 0) legend.appendChild(el('span', null, '+' + chart.legendMore + ' more'));
        section.appendChild(legend);
      }

      const svg = svgEl('svg', { viewBox: '0 0 ' + chart.width + ' ' + chart.height }, 'chart');
      const p = chart.plot;
      chart.valueTicks.forEach((tick) => {
        if (chart.horizontal) {
          svg.appendChild(svgEl('line', { x1: tick.pos, y1: p[1], x2: tick.pos, y2: p[3] }, 'grid'));
          const text = svgEl('text', { x: tick.pos, y: p[3] + 18, 'text-anchor': 'middle' });
          text.textContent = tick.label;
          svg.appendChild(text);
        } else {
          svg.appendChild(svgEl('line', { x1: p[0], y1: tick.pos, x2: p[2], y2: tick.pos }, 'grid'));
          const text = svgEl('text', { x: p[0] - 8, y: tick.pos + 4, 'text-anchor': 'end' });
          text.textContent = tick.label;
          svg.appendChild(text);
        }
      });
      chart.categoryTicks.forEach((tick) => {
        const attrs = chart.horizontal
          ? { x: p[0] - 8, y: tick.pos + 4, 'text-anchor': 'end' }
          : { x: tick.pos, y: p[3] + 18, 'text-anchor': 'middle' };
        const text = svgEl('text', attrs);
        text.textContent = tick.label;
        svg.appendChild(text);
      });
      svg.appendChild(svgEl('line', { x1: p[0], y1: p[1], x2: p[0], y2: p[3] }, 'axis'));
      svg.appendChild(svgEl('line', { x1: p[0], y1: p[3], x2: p[2], y2: p[3] }, 'axis'));

      chart.shapes.forEach((shape) => {
        // series is omitted from the JSON when it is the default 0.
        const seriesClass = 's' + ((shape.series || 0) % SERIES_COUNT);
        let node;
        if (shape.kind === 'rect') {
          node = svgEl('rect', { x: shape.x, y: shape.y, width: shape.w, height: shape.h }, 'shape fill-' + seriesClass);
        } else if (shape.kind === 'circle') {
          node = svgEl('circle', { cx: shape.cx, cy: shape.cy, r: shape.r }, 'shape fill-' + seriesClass);
        } else {
          node = svgEl('polyline', { points: shape.points, fill: 'none', 'stroke-width': 3 }, 'stroke-' + seriesClass);
        }
        if (shape.tooltip) {
          const title = document.createElementNS(SVG_NS, 'title');
          title.textContent = shape.tooltip;
          node.appendChild(title);
        }
        if (shape.d) {
          node.classList.add('drill');
          node.addEventListener('click', () => openDrill(shape.d));
        }
        svg.appendChild(node);
      });
      section.appendChild(svg);
      section.appendChild(el('p', 'hint', 'Hover a mark for details; click it to see its source rows.'));
      app.appendChild(section);
    }

    function renderHeader(meta) {
      const header = el('header');
      header.appendChild(el('h1', null, meta.title));
      const line = el('div', 'meta',
        meta.connectionLabel + ' · Based on ' + meta.sampleRowCount + ' sampled rows · sampled ' +
        meta.sampledAt + ' · generated ' + meta.generatedAt);
      if (meta.sampleTruncated) line.appendChild(el('span', 'badge', 'Sample truncated'));
      header.appendChild(line);
      meta.warnings.forEach((warning) => header.appendChild(el('div', 'warning', warning)));
      app.appendChild(header);
    }

    renderHeader(REPORT.meta);
    if (REPORT.chart) renderChart(REPORT.chart);
    if (REPORT.pivot) renderPivot(REPORT.pivot);
    if (REPORT.table) {
      const section = el('section');
      section.appendChild(el('h2', 'section-title', REPORT.chart ? 'Chart data' : 'Rows'));
      section.appendChild(buildTableWidget(REPORT.table, REPORT.table.rows.some((r) => r.kind !== 'detail')));
      if (REPORT.table.rows.some((r) => r.d)) {
        section.appendChild(el('p', 'hint', 'Click a row to see its source row.'));
      }
      app.appendChild(section);
    }
    dialog.addEventListener('click', (event) => { if (event.target === dialog) dialog.close(); });
    """
        .trimIndent()
