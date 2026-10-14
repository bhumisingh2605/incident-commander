
'use strict';

const STATUS_CLASS = {
  OPEN: 'st-info', INVESTIGATING: 'st-work', DIAGNOSED: 'st-info',
  AWAITING_APPROVAL: 'st-warn', REMEDIATING: 'st-work',
  RESOLVED: 'st-ok', ESCALATED: 'st-bad'
};

let selected = null;
let me = { name: '?', roles: [] };

const $ = id => document.getElementById(id);
const isOperator = () => me.roles.includes('ROLE_OPERATOR');

// Everything from the server is rendered with textContent: log and model text is untrusted.
function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text !== undefined && text !== null) n.textContent = String(text);
  if (cls) n.className = cls;
  return n;
}

function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

function fmtTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? '' : d.toLocaleTimeString();
}

function badge(status) {
  return el('span', status, 'badge ' + (STATUS_CLASS[status] || ''));
}

async function call(path, method) {
  const r = await fetch(path, { method: method || 'GET' });
  const text = await r.text();

  if (!r.ok) {
    throw new Error('HTTP ' + r.status + (text ? ': ' + text.slice(0, 200) : ''));
  }

  try {
    return JSON.parse(text);
  } catch (e) {
    return text;
  }
}

function setMessage(text, isError) {
  const m = $('msg');
  m.textContent = text;
  m.className = isError ? 'err' : '';
  m.style.display = 'block';
}

async function decide(p, action) {
  const verb = action === 'approve' ? 'Approve' : 'Reject';

  if (!confirm(verb + ' ' + p.action_type + ' on ' + p.target_service + '?')) {
    return;
  }

  try {
    const out = await call(
      '/api/proposals/' + encodeURIComponent(p.id) + '/' + action,
      'POST'
    );
    setMessage(String(out), false);
  } catch (e) {
    setMessage(e.message, true);
  }

  refresh();
}

function proposalRow(p, withIncident) {
  const row = el('div', null, 'prop');
  row.appendChild(el('strong', p.action_type + ' ' + p.target_service));

  const meta = [];
  if (withIncident) meta.push('incident #' + p.incident_id);
  meta.push(p.status);
  if (p.decided_by) meta.push('by ' + p.decided_by);

  row.appendChild(el('span', ' ' + meta.join(' · '), 'muted'));

  const note = p.result || p.rationale;
  if (note) row.appendChild(el('div', note, 'muted small'));

  if (p.status === 'PENDING' && isOperator()) {
    const a = el('button', 'Approve', 'btn ok');
    a.onclick = () => decide(p, 'approve');

    const r = el('button', 'Reject', 'btn bad');
    r.onclick = () => decide(p, 'reject');

    row.appendChild(a);
    row.appendChild(r);
  }

  return row;
}

function renderPending(list) {
  const box = $('pending-list');
  clear(box);

  if (!list.length) {
    box.appendChild(el('p', 'Nothing waiting for approval.', 'muted'));
    return;
  }

  list.forEach(p => box.appendChild(proposalRow(p, true)));
}

function renderIncidents(list) {
  const body = $('incidents');
  clear(body);

  list.forEach(i => {
    const tr = el('tr', null, i.id === selected ? 'sel' : '');

    tr.appendChild(el('td', i.id));
    tr.appendChild(el('td', i.title));

    const st = el('td');
    st.appendChild(badge(i.status));
    tr.appendChild(st);

    tr.appendChild(
      el('td', i.rc_service ? i.rc_service + ' / ' + (i.rc_category || '') : '')
    );

    tr.appendChild(
      el(
        'td',
        i.rc_confidence === null || i.rc_confidence === undefined
          ? ''
          : Number(i.rc_confidence).toFixed(2)
      )
    );

    tr.appendChild(el('td', fmtTime(i.created_at)));

    tr.onclick = () => {
      selected = i.id;
      refresh();
    };

    body.appendChild(tr);
  });
}

function section(title) {
  const s = el('div');
  s.appendChild(el('h3', title));
  return s;
}

function lines(text) {
  return (text || '').split('\n').map(s => s.trim()).filter(Boolean);
}

function bulletList(items) {
  const ul = el('ul');
  items.forEach(t => ul.appendChild(el('li', t)));
  return ul;
}

function renderDetail(i, props) {
  const box = $('detail');
  clear(box);

  const h = el('h2', '#' + i.id + ' ' + i.title + ' ');
  h.appendChild(badge(i.status));
  box.appendChild(h);

  // Postmortem link: opens the plain-text report in a new tab.
  const pm = el('a', 'Postmortem (text)', 'muted small');
  pm.href = '/api/incidents/' + encodeURIComponent(i.id) + '/postmortem';
  pm.target = '_blank';
  pm.rel = 'noopener';
  box.appendChild(pm);

  const rc = section('Root cause');

  if (i.rc_summary) {
    rc.appendChild(el('p', i.rc_summary));

    const conf =
      i.rc_confidence === null || i.rc_confidence === undefined
        ? '?'
        : Number(i.rc_confidence).toFixed(2);

    rc.appendChild(
      el(
        'p',
        (i.rc_service || '?') + ' · ' +
        (i.rc_category || '?') + ' · confidence ' + conf,
        'muted'
      )
    );

    if (lines(i.rc_evidence).length) {
      rc.appendChild(el('div', 'Evidence', 'muted small'));
      rc.appendChild(bulletList(lines(i.rc_evidence)));
    }

    if (lines(i.rc_actions).length) {
      rc.appendChild(el('div', 'Suggested actions', 'muted small'));
      rc.appendChild(bulletList(lines(i.rc_actions)));
    }
  } else {
    rc.appendChild(el('p', 'No diagnosis yet.', 'muted'));
  }

  box.appendChild(rc);

  const mine = props.filter(p => p.incident_id === i.id);

  if (mine.length) {
    const s = section('Proposals');
    mine.forEach(p => s.appendChild(proposalRow(p, false)));
    box.appendChild(s);
  }

  if (i.verification) {
    const s = section('Verification');
    s.appendChild(el('p', i.verification));
    box.appendChild(s);
  }

  if (i.alerts && i.alerts.length) {
    const s = section('Alerts');

    s.appendChild(
      bulletList(
        i.alerts.map(a => a.alertname + ' on ' + a.application + ' · ' + a.status)
      )
    );

    box.appendChild(s);
  }

  if (i.agent_runs && i.agent_runs.length) {
    const s = section('Agent runs');

    s.appendChild(
      bulletList(
        i.agent_runs.map(r =>
          r.run_id + ' · ' + r.outcome +
          (r.seconds != null ? ' · ' + r.seconds + 's' : '') +
          (r.tool_calls != null ? ' · ' + r.tool_calls + ' tool calls' : '') +
          (r.error ? ' · ' + r.error : '')
        )
      )
    );

    box.appendChild(s);
  }

  if (i.tool_calls && i.tool_calls.length) {
    const d = el('details');

    d.appendChild(el('summary', 'Tool calls (' + i.tool_calls.length + ')'));

    d.appendChild(
      bulletList(
        i.tool_calls.map(t =>
          t.tool_name + '(' + t.args + ') · ' + t.status
        )
      )
    );

    box.appendChild(d);
  }
}

async function refresh() {
  try {
    const [list, pending, props] = await Promise.all([
      call('/api/incidents'),
      call('/api/proposals?status=PENDING'),
      call('/api/proposals')
    ]);

    renderPending(pending);
    renderIncidents(list);

    if (selected !== null) {
      renderDetail(
        await call('/api/incidents/' + encodeURIComponent(selected)),
        props
      );
    }

    $('updated').textContent = 'updated ' + new Date().toLocaleTimeString();
  } catch (e) {
    setMessage('Refresh failed: ' + e.message, true);
  }
}

async function init() {
  try {
    me = await call('/api/me');

    $('who').textContent =
      me.name + ' (' + me.roles.map(r => r.replace('ROLE_', '')).join(', ') + ')';
  } catch (e) {
    setMessage('Could not load your login: ' + e.message, true);
  }

  await refresh();
  setInterval(refresh, 5000);
}

init();

