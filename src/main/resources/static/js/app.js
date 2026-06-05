// SRE Incident Control Center Dashboard JavaScript
const state = {
    token: localStorage.getItem('sre_token') || null,
    user: JSON.parse(localStorage.getItem('sre_user')) || null,
    incidents: [],
    selectedIncidentId: null,
    pollInterval: null,
    searchTab: 'incidents'
};

const alertTemplates = [
    {
        title: "Hikari connection pool database connection timeout exception",
        description: "Hikari pool-1 connection timeout exception after 30000ms. Failed to obtain DB connection. Current active connections: 10.",
        severity: "SEV1",
        source: "prometheus",
        serviceName: "payment-service",
        environment: "production",
        rawPayload: '{"alert_id":"alert-db-pool-timeout","metric":"db_pool_active_connections","value":10,"threshold":10}'
    },
    {
        title: "Java heap space OutOfMemoryError in critical worker thread",
        description: "JVM Heap usage spiked to 99.1%. Garbage collector thrashing detected, service not responding to health checks.",
        severity: "SEV1",
        source: "datadog",
        serviceName: "auth-service",
        environment: "production",
        rawPayload: '{"alert_id":"alert-jvm-oom","metric":"jvm.memory.heap.pct","value":99.1,"threshold":95}'
    },
    {
        title: "Response latency spike on orders endpoint",
        description: "Endpoint POST /api/v1/orders response latency has spiked. p99 response time is currently 4200ms.",
        severity: "SEV2",
        source: "prometheus",
        serviceName: "order-service",
        environment: "production",
        rawPayload: '{"alert_id":"alert-latency-spike","endpoint":"POST /orders","latency_p99":4200,"threshold":1500}'
    }
];

function getAuthHeaders() {
    return state.token ? {
        'Authorization': `Bearer ${state.token}`,
        'Content-Type': 'application/json'
    } : {
        'Content-Type': 'application/json'
    };
}

function escapeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

// Authentication Flow
async function submitLogin(email, password) {
    const errorMsgDiv = document.getElementById('login-error-msg');
    errorMsgDiv.classList.add('hidden');
    errorMsgDiv.textContent = '';
    
    try {
        const response = await fetch('/api/v1/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });
        
        if (!response.ok) {
            const errData = await response.json().catch(() => ({}));
            throw new Error(errData.message || 'Authentication failed. Please verify credentials.');
        }
        
        const data = await response.json();
        state.token = data.token;
        state.user = {
            email: data.email,
            role: data.role,
            firstName: data.firstName,
            lastName: data.lastName
        };
        
        localStorage.setItem('sre_token', state.token);
        localStorage.setItem('sre_user', JSON.stringify(state.user));
        
        showDashboard();
    } catch (err) {
        errorMsgDiv.textContent = err.message;
        errorMsgDiv.classList.remove('hidden');
    }
}

function showDashboard() {
    document.getElementById('login-overlay').classList.add('hidden');
    document.getElementById('dashboard-app').classList.remove('hidden');
    
    document.getElementById('user-email-display').textContent = state.user.email;
    document.getElementById('user-role-display').textContent = state.user.role;
    
    fetchIncidents();
    if (state.pollInterval) clearInterval(state.pollInterval);
    state.pollInterval = setInterval(fetchIncidents, 4000);
}

function logout() {
    state.token = null;
    state.user = null;
    state.selectedIncidentId = null;
    localStorage.removeItem('sre_token');
    localStorage.removeItem('sre_user');
    
    if (state.pollInterval) {
        clearInterval(state.pollInterval);
        state.pollInterval = null;
    }
    
    document.getElementById('dashboard-app').classList.add('hidden');
    document.getElementById('login-overlay').classList.remove('hidden');
    document.getElementById('detail-content').classList.add('hidden');
    document.getElementById('detail-empty-state').classList.remove('hidden');
}

function checkAuth() {
    if (state.token && state.user) {
        showDashboard();
    } else {
        logout();
    }
}

// Incidents List & Polling
async function fetchIncidents() {
    try {
        const response = await fetch('/api/v1/incidents?page=0&size=100&sort=createdAt,desc', {
            headers: getAuthHeaders()
        });
        
        if (response.status === 401 || response.status === 403) {
            logout();
            return;
        }
        
        if (!response.ok) throw new Error('Failed to fetch incidents');
        
        const data = await response.json();
        state.incidents = data.content || [];
        renderIncidentList();
        
        if (state.selectedIncidentId) {
            fetchIncidentDetail(state.selectedIncidentId);
        }
    } catch (err) {
        console.error('Error fetching incidents:', err);
    }
}

function renderIncidentList() {
    const searchVal = document.getElementById('filter-search').value.toLowerCase();
    const showOpen = document.getElementById('filter-status-open').checked;
    const showInvestigating = document.getElementById('filter-status-investigating').checked;
    const showResolved = document.getElementById('filter-status-resolved').checked;
    
    const container = document.getElementById('incident-list-container');
    
    const filtered = state.incidents.filter(inc => {
        const status = inc.status;
        if (status === 'OPEN' && !showOpen) return false;
        if (status === 'INVESTIGATING' && !showInvestigating) return false;
        if (status === 'RESOLVED' && !showResolved) return false;
        if (status === 'CLOSED' && !showResolved) return false;
        
        if (searchVal) {
            const titleMatch = inc.title && inc.title.toLowerCase().includes(searchVal);
            const descMatch = inc.description && inc.description.toLowerCase().includes(searchVal);
            const serviceMatch = inc.serviceName && inc.serviceName.toLowerCase().includes(searchVal);
            return titleMatch || descMatch || serviceMatch;
        }
        return true;
    });
    
    document.getElementById('incident-count').textContent = `${filtered.length} Active`;
    
    if (filtered.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <p>No incidents match criteria.</p>
            </div>
        `;
        return;
    }
    
    container.innerHTML = '';
    filtered.forEach(inc => {
        const item = document.createElement('div');
        item.className = `incident-card ${state.selectedIncidentId === inc.id ? 'active' : ''}`;
        
        let statusClass = 'open';
        if (inc.status === 'INVESTIGATING') statusClass = 'investigating';
        if (inc.status === 'RESOLVED') statusClass = 'resolved';
        if (inc.status === 'CLOSED') statusClass = 'closed';
        
        const sevClass = inc.severity ? inc.severity.toLowerCase() : 'sev4';
        const dateStr = inc.createdAt ? new Date(inc.createdAt).toLocaleTimeString() : '';
        
        item.innerHTML = `
            <div class="card-title-row">
                <span class="severity-badge ${sevClass}">${inc.severity || 'SEV4'}</span>
                <span class="card-status ${statusClass}">${inc.status}</span>
            </div>
            <h4 class="card-title" style="margin-top: 8px;">${escapeHtml(inc.title)}</h4>
            <div class="card-meta-row">
                <span class="card-service">${escapeHtml(inc.serviceName)}</span>
                <span class="card-time">${dateStr}</span>
            </div>
        `;
        
        item.addEventListener('click', () => {
            selectIncident(inc.id);
        });
        
        container.appendChild(item);
    });
}

function selectIncident(id) {
    state.selectedIncidentId = id;
    renderIncidentList();
    
    document.getElementById('detail-empty-state').classList.add('hidden');
    document.getElementById('detail-content').classList.remove('hidden');
    
    fetchIncidentDetail(id);
}

async function fetchIncidentDetail(id) {
    try {
        const resIncident = await fetch(`/api/v1/incidents/${id}`, {
            headers: getAuthHeaders()
        });
        if (!resIncident.ok) throw new Error('Failed to load incident detail');
        const incident = await resIncident.json();
        
        const resInv = await fetch(`/api/v1/incidents/${id}/investigations`, {
            headers: getAuthHeaders()
        });
        const investigations = resInv.ok ? await resInv.json() : [];
        
        if (state.selectedIncidentId !== id) return;
        
        renderIncidentDetailView(incident, investigations);
    } catch (err) {
        console.error('Error fetching incident detail:', err);
    }
}

function renderIncidentDetailView(incident, investigations) {
    if (!incident) return;
    
    document.getElementById('detail-title').textContent = incident.title || 'Incident Detail';
    
    const severityBadge = document.getElementById('detail-severity');
    severityBadge.className = `severity-badge ${incident.severity ? incident.severity.toLowerCase() : 'sev4'}`;
    severityBadge.textContent = incident.severity || 'SEV4';
    
    const statusIndicator = document.getElementById('detail-status');
    statusIndicator.className = `status-indicator status-${incident.status.toLowerCase()}`;
    statusIndicator.textContent = incident.status;
    
    document.getElementById('detail-service').textContent = incident.serviceName || 'N/A';
    document.getElementById('detail-env').textContent = incident.environment || 'N/A';
    document.getElementById('detail-id').textContent = incident.id || '';
    document.getElementById('detail-desc').textContent = incident.description || 'No description provided.';
    
    const steps = [
        { id: 'step-planner', type: 'PLANNER' },
        { id: 'step-log-metrics', type: 'LOG_METRICS' },
        { id: 'step-knowledge', type: 'KNOWLEDGE' },
        { id: 'step-rca', type: 'RCA_RECOMMENDATION' }
    ];
    
    let firstPendingFound = false;
    
    steps.forEach((step) => {
        const stepElem = document.getElementById(step.id);
        const statusTextElem = stepElem.querySelector('.step-status');
        const match = investigations.find(i => i.agentType === step.type);
        
        if (match) {
            stepElem.classList.remove('active', 'error');
            stepElem.classList.add('success');
            statusTextElem.textContent = 'COMPLETED';
            
            if (step.type === 'PLANNER') {
                const listContainer = document.getElementById('step-planner-list');
                listContainer.innerHTML = '';
                const lines = match.findings ? match.findings.split('\n') : [];
                lines.forEach(line => {
                    const trimmed = line.trim();
                    if (trimmed) {
                        const li = document.createElement('li');
                        li.textContent = trimmed.replace(/^\d+[\.\-\s]*/, '');
                        
                        const hasLaterStep = investigations.some(i => i.agentType !== 'PLANNER');
                        if (hasLaterStep || incident.status === 'RESOLVED' || incident.status === 'CLOSED') {
                            li.classList.add('done');
                        }
                        listContainer.appendChild(li);
                    }
                });
                document.getElementById('step-planner-reasoning').textContent = match.reasoning || '';
            } else if (step.type === 'LOG_METRICS') {
                document.getElementById('step-log-output').textContent = match.findings || '';
                document.getElementById('step-log-reasoning').textContent = match.reasoning || '';
            } else if (step.type === 'KNOWLEDGE') {
                const kbDocContainer = document.getElementById('step-knowledge-document');
                kbDocContainer.innerHTML = `<div class="runbook-text" style="white-space: pre-wrap;">${escapeHtml(match.findings || '')}</div>`;
                document.getElementById('step-knowledge-reasoning').textContent = match.reasoning || '';
            } else if (step.type === 'RCA_RECOMMENDATION') {
                const confidencePercent = match.confidenceScore ? Math.round(parseFloat(match.confidenceScore) * 100) : 90;
                document.getElementById('detail-rca-score').textContent = `${confidencePercent}%`;
                
                let rcaSummary = match.findings || '';
                let rcaRecommendations = "Check system metrics and verify connectivity pool releases.";
                const remIdx = rcaSummary.toUpperCase().indexOf("REMEDIATION");
                if (remIdx !== -1) {
                    const originalSummary = rcaSummary;
                    rcaSummary = originalSummary.substring(0, remIdx).trim();
                    rcaRecommendations = originalSummary.substring(remIdx).replace(/^(REMEDIATION ACTIONS:|REMEDIATION:)/i, '').trim();
                }
                
                document.getElementById('detail-rca-summary').textContent = rcaSummary;
                document.getElementById('detail-rca-recommendations').textContent = rcaRecommendations;
                document.getElementById('step-rca-reasoning').textContent = match.reasoning || '';
            }
        } else {
            stepElem.classList.remove('success', 'error');
            
            if (incident.status === 'INVESTIGATING' && !firstPendingFound) {
                stepElem.classList.add('active');
                statusTextElem.textContent = 'RUNNING';
                firstPendingFound = true;
            } else {
                stepElem.classList.remove('active');
                statusTextElem.textContent = 'PENDING';
            }
            
            if (step.type === 'PLANNER') {
                document.getElementById('step-planner-list').innerHTML = '<li>Awaiting initialization...</li>';
                document.getElementById('step-planner-reasoning').textContent = '';
            } else if (step.type === 'LOG_METRICS') {
                document.getElementById('step-log-output').textContent = 'Awaiting log metrics extraction...';
                document.getElementById('step-log-reasoning').textContent = '';
            } else if (step.type === 'KNOWLEDGE') {
                document.getElementById('step-knowledge-document').innerHTML = '<div class="runbook-text">Awaiting vector context search...</div>';
                document.getElementById('step-knowledge-reasoning').textContent = '';
            } else if (step.type === 'RCA_RECOMMENDATION') {
                document.getElementById('detail-rca-score').textContent = '0%';
                document.getElementById('detail-rca-summary').textContent = 'Awaiting final analysis...';
                document.getElementById('detail-rca-recommendations').textContent = 'Awaiting remediation guidelines...';
                document.getElementById('step-rca-reasoning').textContent = '';
            }
        }
    });
}

// Ingestion Form Submissions
async function setupIngestionForm() {
    const ingestForm = document.getElementById('ingest-form');
    ingestForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const title = document.getElementById('ingest-title').value;
        const serviceName = document.getElementById('ingest-service').value;
        const tagsStr = document.getElementById('ingest-tags').value;
        const content = document.getElementById('ingest-content').value;
        
        const tags = tagsStr.split(',').map(t => t.trim()).filter(Boolean);
        const msgElem = document.getElementById('ingest-msg');
        msgElem.className = 'modal-msg hidden';
        
        try {
            const response = await fetch('/api/v1/documents/ingest', {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify({ title, serviceName, tags, content })
            });
            
            if (!response.ok) {
                const errData = await response.json().catch(() => ({}));
                throw new Error(errData.message || 'Ingestion failed.');
            }
            
            msgElem.textContent = 'Runbook successfully vector-indexed and uploaded!';
            msgElem.className = 'modal-msg success';
            ingestForm.reset();
            
            setTimeout(() => {
                document.getElementById('modal-ingest').classList.add('hidden');
                msgElem.className = 'modal-msg hidden';
            }, 2000);
        } catch (err) {
            msgElem.textContent = err.message;
            msgElem.className = 'modal-msg error';
        }
    });
}

// Search Operations
async function runIncidentSearch() {
    const query = document.getElementById('search-incidents-input').value;
    const resultsContainer = document.getElementById('search-incidents-results');
    resultsContainer.innerHTML = '<div class="empty-results">Searching incidents...</div>';
    
    try {
        const response = await fetch(`/api/v1/search/incidents?query=${encodeURIComponent(query)}`, {
            headers: getAuthHeaders()
        });
        
        if (!response.ok) throw new Error('Search failed');
        const data = await response.json();
        
        if (data.length === 0) {
            resultsContainer.innerHTML = '<div class="empty-results">No incidents match search criteria.</div>';
            return;
        }
        
        resultsContainer.innerHTML = '';
        data.forEach(doc => {
            const item = document.createElement('div');
            item.className = 'search-item';
            item.innerHTML = `
                <div class="search-item-header">
                    <span class="search-item-title">${escapeHtml(doc.title)}</span>
                    <span class="severity-badge ${doc.severity.toLowerCase()}">${doc.severity}</span>
                </div>
                <p class="search-item-desc" style="font-size: 0.85rem; color: var(--text-secondary); margin-bottom: 6px;">${escapeHtml(doc.description)}</p>
                <div style="font-size: 0.75rem; color: var(--text-muted); display: flex; justify-content: space-between;">
                    <span>Service: <b class="mono">${escapeHtml(doc.serviceName)}</b> | Status: <b>${doc.status}</b></span>
                    <span>${doc.createdAt ? new Date(doc.createdAt).toLocaleString() : ''}</span>
                </div>
                ${doc.rcaSummary ? `<div style="margin-top:8px; padding: 8px; background: rgba(16,185,129,0.05); border: 1px solid rgba(16,185,129,0.1); border-radius: 4px; font-size: 0.8rem;"><span style="color:var(--color-resolved); font-weight:700;">RCA:</span> ${escapeHtml(doc.rcaSummary)}</div>` : ''}
            `;
            resultsContainer.appendChild(item);
        });
    } catch (err) {
        resultsContainer.innerHTML = `<div class="empty-results" style="color:var(--color-sev1)">Error: ${err.message}</div>`;
    }
}

async function runLogsSearch() {
    const serviceName = document.getElementById('search-logs-service').value;
    const query = document.getElementById('search-logs-query').value;
    const resultsContainer = document.getElementById('search-logs-results');
    
    if (!serviceName) {
        resultsContainer.innerHTML = '<div class="empty-results" style="color:var(--color-sev1)">Service name is required.</div>';
        return;
    }
    
    resultsContainer.innerHTML = '<div class="empty-results">Searching service logs...</div>';
    
    try {
        const url = `/api/v1/search/logs?serviceName=${encodeURIComponent(serviceName)}&query=${encodeURIComponent(query)}`;
        const response = await fetch(url, {
            headers: getAuthHeaders()
        });
        
        if (!response.ok) throw new Error('Logs search failed');
        const data = await response.json();
        
        if (data.length === 0) {
            resultsContainer.innerHTML = '<div class="empty-results">No logs matched service and message query.</div>';
            return;
        }
        
        resultsContainer.innerHTML = '';
        data.forEach(logDoc => {
            const item = document.createElement('div');
            item.className = 'search-item';
            const timeStr = logDoc.timestamp ? new Date(logDoc.timestamp).toLocaleTimeString() : '';
            
            let levelClass = 'status-open';
            if (logDoc.logLevel === 'INFO') levelClass = 'status-resolved';
            if (logDoc.logLevel === 'WARN') levelClass = 'status-investigating';
            
            item.innerHTML = `
                <div class="search-item-header">
                    <span class="status-indicator ${levelClass}" style="font-size:0.65rem;">${logDoc.logLevel}</span>
                    <span style="font-size:0.75rem; color:var(--text-muted);">${timeStr}</span>
                </div>
                <pre style="font-family:var(--font-mono); font-size:0.8rem; background:rgba(2,6,23,0.3); padding:8px; border-radius:4px; margin-top:6px; color:#e2e8f0; white-space:pre-wrap;">${escapeHtml(logDoc.message)}</pre>
            `;
            resultsContainer.appendChild(item);
        });
    } catch (err) {
        resultsContainer.innerHTML = `<div class="empty-results" style="color:var(--color-sev1)">Error: ${err.message}</div>`;
    }
}

// Alert Simulation Trigger
async function simulateIncident() {
    const template = alertTemplates[Math.floor(Math.random() * alertTemplates.length)];
    
    try {
        const response = await fetch('/api/v1/alerts', {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify(template)
        });
        
        if (!response.ok) {
            const errData = await response.json().catch(() => ({}));
            throw new Error(errData.message || 'Alert injection failed');
        }
        
        alert('Alert Simulated!\nTitle: ' + template.title + '\n\nPublished to Kafka. The SRE Agent pipeline execution will begin shortly.');
        fetchIncidents();
    } catch (err) {
        alert('Failed to simulate alert: ' + err.message);
    }
}

// Event Listeners Setup
function setupEventListeners() {
    // Quick credentials login badge handler
    document.querySelectorAll('.credential-badge').forEach(badge => {
        badge.addEventListener('click', (e) => {
            const btn = e.currentTarget;
            const email = btn.getAttribute('data-email');
            const password = btn.getAttribute('data-password');
            document.getElementById('login-email').value = email;
            document.getElementById('login-password').value = password;
            submitLogin(email, password);
        });
    });
    
    // Custom login form submit
    document.getElementById('login-form').addEventListener('submit', (e) => {
        e.preventDefault();
        const email = document.getElementById('login-email').value;
        const password = document.getElementById('login-password').value;
        submitLogin(email, password);
    });
    
    // Logout
    document.getElementById('btn-logout').addEventListener('click', logout);
    
    // Simulated alert trigger
    document.getElementById('btn-simulate-alert').addEventListener('click', simulateIncident);
    
    // Runbook modals togglers
    document.getElementById('btn-open-ingest').addEventListener('click', () => {
        document.getElementById('modal-ingest').classList.remove('hidden');
    });
    document.getElementById('btn-close-ingest').addEventListener('click', () => {
        document.getElementById('modal-ingest').classList.add('hidden');
    });
    document.getElementById('btn-cancel-ingest').addEventListener('click', () => {
        document.getElementById('modal-ingest').classList.add('hidden');
    });
    
    // Search modal togglers
    document.getElementById('btn-open-search').addEventListener('click', () => {
        document.getElementById('modal-search').classList.remove('hidden');
    });
    document.getElementById('btn-close-search').addEventListener('click', () => {
        document.getElementById('modal-search').classList.add('hidden');
    });
    
    // Search modal tabs handlers
    const tabIncidents = document.getElementById('tab-search-incidents');
    const tabLogs = document.getElementById('tab-search-logs');
    const paneIncidents = document.getElementById('pane-search-incidents');
    const paneLogs = document.getElementById('pane-search-logs');
    
    tabIncidents.addEventListener('click', () => {
        tabIncidents.classList.add('active');
        tabLogs.classList.remove('active');
        paneIncidents.classList.remove('hidden');
        paneLogs.classList.add('hidden');
        state.searchTab = 'incidents';
    });
    
    tabLogs.addEventListener('click', () => {
        tabLogs.classList.add('active');
        tabIncidents.classList.remove('active');
        paneLogs.classList.remove('hidden');
        paneIncidents.classList.add('hidden');
        state.searchTab = 'logs';
    });
    
    // Search action buttons
    document.getElementById('btn-run-search-incidents').addEventListener('click', runIncidentSearch);
    document.getElementById('search-incidents-input').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') runIncidentSearch();
    });
    
    document.getElementById('btn-run-search-logs').addEventListener('click', runLogsSearch);
    document.getElementById('search-logs-query').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') runLogsSearch();
    });
    document.getElementById('search-logs-service').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') runLogsSearch();
    });
    
    // Sidebar list filters
    document.getElementById('filter-search').addEventListener('input', renderIncidentList);
    document.getElementById('filter-status-open').addEventListener('change', renderIncidentList);
    document.getElementById('filter-status-investigating').addEventListener('change', renderIncidentList);
    document.getElementById('filter-status-resolved').addEventListener('change', renderIncidentList);
}

// Entrypoint
document.addEventListener('DOMContentLoaded', () => {
    setupEventListeners();
    setupIngestionForm();
    checkAuth();
});
