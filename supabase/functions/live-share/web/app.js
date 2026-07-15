(() => {
  const token = new URLSearchParams(location.hash.slice(1)).get('token') || '';
  const endpoint = `${location.origin}/functions/v1/live-share`;
  const nameElement = document.getElementById('name');
  const statusElement = document.getElementById('status');
  const expiresElement = document.getElementById('expires');
  const messageElement = document.getElementById('message');
  const recenterButton = document.getElementById('recenter');
  const map = L.map('map', { zoomControl: true }).setView([56.84, 60.61], 12);
  const path = L.polyline([], { color: '#6748ad', weight: 5, opacity: .92, lineJoin: 'round' }).addTo(map);
  const markerIcon = L.divIcon({ className: '', html: '<div class="latest-marker"></div>', iconSize: [24, 24], iconAnchor: [12, 12] });
  let marker = null;
  let cursor = null;
  let follow = true;
  let programmaticMove = false;
  let timer = null;
  let expiresAt = null;
  let latestRecordedAt = null;
  let hasFittedInitialPath = false;
  let refreshInFlight = false;
  let visiblePoints = [];
  let tilesUnavailable = false;

  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 20,
    attribution: '&copy; OpenStreetMap contributors'
  }).on('tileerror', () => {
    tilesUnavailable = true;
    showMessage('Подложка карты недоступна. Координаты продолжают обновляться.');
  }).on('tileload', () => {
    if (!tilesUnavailable) return;
    tilesUnavailable = false;
    hideMessage();
  }).addTo(map);

  function showMessage(text) {
    messageElement.textContent = text;
    messageElement.style.display = 'block';
  }

  function hideMessage() {
    messageElement.textContent = '';
    messageElement.style.display = 'none';
  }

  function relativeTime(value) {
    if (!value) return 'Ожидаем координаты';
    const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
    if (seconds < 30) return 'Обновлено сейчас';
    if (seconds < 60) return `Обновлено ${seconds} сек назад`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `Обновлено ${minutes} мин назад`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `Обновлено ${hours} ч назад`;
    return `Обновлено ${new Date(value).toLocaleString('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })}`;
  }

  function updateLabels() {
    pruneVisiblePath();
    statusElement.textContent = relativeTime(latestRecordedAt);
    if (!expiresAt) {
      expiresElement.textContent = 'Ссылка действует до ручного отключения';
      return;
    }
    const remaining = new Date(expiresAt).getTime() - Date.now();
    if (remaining <= 0) {
      expiresElement.textContent = 'Трансляция завершена';
      return;
    }
    const minutes = Math.max(1, Math.ceil(remaining / 60000));
    const hours = Math.floor(minutes / 60);
    expiresElement.textContent = hours > 0
      ? `Ссылка активна ещё ${hours} ч ${minutes % 60} мин`
      : `Ссылка активна ещё ${minutes} мин`;
  }

  function pruneVisiblePath() {
    if (visiblePoints.length === 0) return [];
    const cutoff = Date.now() - 24 * 60 * 60 * 1000;
    const recentPoints = visiblePoints.filter((point) => new Date(point.recordedAt).getTime() >= cutoff);
    visiblePoints = recentPoints.length > 0
      ? recentPoints
      : [visiblePoints[visiblePoints.length - 1]];
    const latLngs = visiblePoints.map((point) => L.latLng(point.latitude, point.longitude));
    path.setLatLngs(latLngs);
    return latLngs;
  }

  function appendPoints(points) {
    if (!Array.isArray(points) || points.length === 0) return;
    points.forEach((point) => {
      const previous = visiblePoints[visiblePoints.length - 1];
      if (previous && previous.latitude === point.latitude && previous.longitude === point.longitude) {
        visiblePoints[visiblePoints.length - 1] = point;
      } else {
        visiblePoints.push(point);
      }
      latestRecordedAt = point.recordedAt;
    });

    const latLngs = pruneVisiblePath();
    const latest = latLngs[latLngs.length - 1];
    if (!marker) marker = L.marker(latest, { icon: markerIcon, interactive: false }).addTo(map);
    else marker.setLatLng(latest);
    recenterButton.style.display = 'block';

    programmaticMove = true;
    if (!hasFittedInitialPath && latLngs.length > 1) {
      map.fitBounds(path.getBounds(), { padding: [48, 100], maxZoom: 17, animate: false });
      hasFittedInitialPath = true;
    } else if (follow) {
      map.setView(latest, Math.max(map.getZoom(), 15), { animate: true, duration: .45 });
    }
    setTimeout(() => { programmaticMove = false; }, 500);
  }

  function stopWithMessage(text) {
    if (timer) clearTimeout(timer);
    timer = null;
    statusElement.textContent = text;
    expiresElement.textContent = '';
    showMessage(text);
  }

  async function refresh() {
    if (refreshInFlight) return;
    if (!token) {
      stopWithMessage('Ссылка повреждена или неполная');
      return;
    }
    refreshInFlight = true;
    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        cache: 'no-store',
        body: JSON.stringify({ token, cursor })
      });
      if (response.status === 404) return stopWithMessage('Ссылка не найдена');
      if (response.status === 410) return stopWithMessage('Трансляция завершена');
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      nameElement.textContent = data.displayName || 'Пользователь ICU';
      expiresAt = data.expiresAt || null;
      appendPoints(data.points || []);
      cursor = data.cursor || cursor;
      updateLabels();
      if (!tilesUnavailable) hideMessage();
    } catch (_) {
      showMessage('Не удалось обновить геопозицию. Повторяем подключение...');
    } finally {
      refreshInFlight = false;
      if (timer) clearTimeout(timer);
      timer = setTimeout(refresh, 10000);
    }
  }

  map.on('dragstart zoomstart', () => {
    if (!programmaticMove) follow = false;
  });
  recenterButton.addEventListener('click', () => {
    if (!marker) return;
    follow = true;
    programmaticMove = true;
    map.setView(marker.getLatLng(), Math.max(map.getZoom(), 15), { animate: true, duration: .45 });
    setTimeout(() => { programmaticMove = false; }, 500);
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') refresh();
  });
  setInterval(updateLabels, 15000);
  refresh();
})();
