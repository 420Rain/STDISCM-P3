const gallery = document.getElementById('gallery');
const queueBody = document.getElementById('queue-body');
const configSummary = document.getElementById('config-summary');
const modal = document.getElementById('video-modal');
const player = document.getElementById('full-player');
const modalTitle = document.getElementById('modal-title');

// Keep video cards by videoId. Rebuilding the entire gallery every poll would
// destroy each <video> element and interrupt a hover preview before 10 seconds.
const videoCards = new Map();

function makeElement(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function createVideoCard(video) {
  const card = makeElement('article', 'video-card');
  const preview = document.createElement('video');
  preview.className = 'video-preview';
  preview.src = `/media/${encodeURIComponent(video.videoId)}`;
  preview.muted = true;
  preview.playsInline = true;
  preview.preload = 'metadata';

  let previewTimer;
  card.addEventListener('mouseenter', () => {
    clearTimeout(previewTimer);
    try { preview.currentTime = 0; } catch (_) { /* Metadata may still be loading. */ }
    preview.play().catch(() => {});
    // A wall-clock limit is retained as a fallback; timeupdate below also
    // stops at 10 media seconds if decoding/network startup takes a moment.
    previewTimer = setTimeout(() => preview.pause(), 10_000);
  });
  card.addEventListener('mouseleave', () => {
    clearTimeout(previewTimer);
    preview.pause();
    try { preview.currentTime = 0; } catch (_) { /* Ignore early metadata state. */ }
  });
  preview.addEventListener('timeupdate', () => {
    if (preview.currentTime >= 10) {
      preview.pause();
      clearTimeout(previewTimer);
    }
  });

  const cardBody = makeElement('div', 'video-card-body');
  const title = makeElement('div', 'video-title', video.originalName);
  const date = makeElement('div', 'video-date', new Date(video.storedAt).toLocaleString());
  const actions = makeElement('div', 'card-actions');
  const watch = makeElement('button', 'watch-button', 'Watch video');
  const remove = makeElement('button', 'delete-button', 'Delete');

  const openPlayer = () => {
    modalTitle.textContent = video.originalName;
    player.src = `/media/${encodeURIComponent(video.videoId)}`;
    modal.classList.remove('hidden');
    player.play().catch(() => {});
  };
  card.addEventListener('click', openPlayer);
  watch.addEventListener('click', (event) => { event.stopPropagation(); openPlayer(); });
  remove.addEventListener('click', async (event) => {
    event.stopPropagation();
    if (!confirm(`Delete ${video.originalName} from consumer storage?`)) return;
    const response = await fetch(`/api/videos/${encodeURIComponent(video.videoId)}`, { method: 'DELETE' });
    const result = await response.json();
    if (!result.success) alert(result.message);
    await refresh();
  });

  actions.append(watch, remove);
  cardBody.append(title, date, actions);
  card.append(preview, cardBody);
  return card;
}

function renderVideos(videos) {
  const incomingIds = new Set(videos.map(video => video.videoId));

  // Remove only videos that disappeared from storage. Existing preview elements
  // are kept in the DOM; this avoids restarting them during the 3-second poll.
  for (const [videoId, card] of videoCards.entries()) {
    if (!incomingIds.has(videoId)) {
      card.remove();
      videoCards.delete(videoId);
    }
  }

  const emptyState = gallery.querySelector('.empty-state');
  if (videos.length === 0) {
    if (!emptyState) {
      gallery.replaceChildren(makeElement('div', 'empty-state', 'No videos have been uploaded yet.'));
    }
    return;
  }
  if (emptyState) emptyState.remove();

  const orderedCards = videos.map(video => {
    let card = videoCards.get(video.videoId);
    if (!card) {
      card = createVideoCard(video);
      videoCards.set(video.videoId, card);
    }
    return card;
  });

  // Reorder only when the API's order really changed. insertBefore moves an
  // existing card without replacing its video element or restarting playback.
  orderedCards.forEach((card, index) => {
    const currentAtPosition = gallery.children[index];
    if (currentAtPosition !== card) {
      gallery.insertBefore(card, currentAtPosition || null);
    }
  });
}

function renderQueue(uploads) {
  queueBody.replaceChildren();
  if (uploads.length === 0) {
    const row = document.createElement('tr');
    const cell = makeElement('td', 'empty-cell', 'No active uploads.');
    cell.colSpan = 4;
    row.append(cell);
    queueBody.append(row);
    return;
  }

  for (const upload of uploads) {
    const row = document.createElement('tr');
    row.append(makeElement('td', 'file-cell', upload.originalName));
    row.append(makeElement('td', 'id-cell', upload.uploadId));
    const statusCell = document.createElement('td');
    statusCell.append(makeElement('span', `status-pill status-${upload.status.toLowerCase()}`, upload.status));
    row.append(statusCell);
    const actionCell = document.createElement('td');
    if (upload.status === 'RECEIVING' || upload.status === 'QUEUED') {
      const cancel = makeElement('button', 'cancel-button', 'Cancel');
      cancel.addEventListener('click', async () => {
        const response = await fetch(`/api/uploads/${encodeURIComponent(upload.uploadId)}`, { method: 'DELETE' });
        const result = await response.json();
        if (!result.success) alert(result.message);
        await refresh();
      });
      actionCell.append(cancel);
    } else {
      actionCell.append(makeElement('span', 'muted', 'In progress'));
    }
    row.append(actionCell);
    queueBody.append(row);
  }
}

function renderConfig(config) {
  configSummary.replaceChildren();
  const values = [
    ['Producer threads (p)', config.p],
    ['Consumer threads (c)', config.c],
    ['Max queue length (q)', config.q]
  ];
  for (const [label, value] of values) {
    const item = makeElement('div', 'config-item');
    item.append(makeElement('span', '', label), makeElement('strong', '', String(value)));
    configSummary.append(item);
  }
}

async function refresh() {
  try {
    // Poll the data as before, but renderVideos now performs a keyed DOM update.
    // Existing preview elements are not reconstructed on every refresh.
    const [videosResponse, uploadsResponse] = await Promise.all([
      fetch('/api/videos', { cache: 'no-store' }),
      fetch('/api/uploads', { cache: 'no-store' })
    ]);
    if (!videosResponse.ok || !uploadsResponse.ok) throw new Error('Could not refresh gallery data.');
    renderVideos(await videosResponse.json());
    renderQueue(await uploadsResponse.json());
  } catch (error) {
    console.error(error);
  }
}

async function loadConfig() {
  try {
    const response = await fetch('/api/config', { cache: 'no-store' });
    if (!response.ok) throw new Error('Could not load application configuration.');
    renderConfig(await response.json());
  } catch (error) {
    console.error(error);
    configSummary.textContent = 'Could not load p, c, q configuration.';
  }
}

function closePlayer() {
  player.pause();
  player.removeAttribute('src');
  player.load();
  modal.classList.add('hidden');
}

document.getElementById('close-modal').addEventListener('click', closePlayer);
modal.addEventListener('click', (event) => {
  if (event.target.dataset.close === 'true') closePlayer();
});
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closePlayer();
});

loadConfig();
refresh();
// Queue status is polled every 3 seconds. Video card elements are preserved
// between polls, so a hover preview can run its full 10 seconds.
setInterval(refresh, 3000);
