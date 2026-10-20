// Runs inline before islands start; it must not depend on another hashed bundle.
(() => {
  let navigating = false;
  const handled = new WeakSet();
  document.addEventListener('astro:hydration-error', async (event) => {
    const island = event.target;
    if (!(island instanceof HTMLElement) || island.tagName !== 'ASTRO-ISLAND' || handled.has(island)) return;
    handled.add(island);

    const urls = ['component-url', 'renderer-url'].flatMap((attribute) => {
      const value = island.getAttribute(attribute);
      if (!value) return [];
      const url = new URL(value, location.href);
      return url.origin === location.origin && url.pathname.startsWith('/_astro/') ? [url] : [];
    });
    const missing = await Promise.all(urls.map(async (url) => {
      try {
        const response = await fetch(url, { method: 'HEAD', cache: 'no-store' });
        return response.status === 404 || response.status === 410;
      } catch {
        return false;
      }
    }));
    if (navigating) return;
    if (missing.some(Boolean)) {
      try {
        // One automatic refresh per page in this tab. Never loop if the deployment is broken.
        const key = 'secman:chunk-refresh:' + location.pathname + location.search;
        if (!sessionStorage.getItem(key)) {
          sessionStorage.setItem(key, '1');
          navigating = true;
          location.reload();
          return;
        }
      } catch {
        // Without storage we cannot enforce the reload limit; show the fallback instead.
      }
    }

    const notice = document.createElement('div');
    notice.className = 'alert alert-danger';
    notice.setAttribute('role', 'alert');
    notice.textContent = 'This section could not load. Refresh the page to try again. If the problem continues, contact your administrator.';
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'btn btn-outline-danger ms-2';
    retry.textContent = 'Refresh page';
    retry.addEventListener('click', () => location.reload());
    notice.append(retry);
    island.replaceChildren(notice);
  });
})();
