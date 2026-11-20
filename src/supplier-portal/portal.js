const redeem = document.querySelector('#redeem');

function useInvitationFragment() {
  if (!redeem || !location.hash) return;
  const invitation = location.hash.slice(1);
  history.replaceState(null, '', '/');
  const valid = /^[a-f0-9-]{36}\.[A-Za-z0-9_-]{43}$/.test(invitation);
  redeem.elements.invitation.value = valid ? invitation : '';
  redeem.hidden = !valid;
}

if (redeem) {
  useInvitationFragment();
  window.addEventListener('hashchange', useInvitationFragment);
}

const draft = document.querySelector('[data-draft]');
draft?.addEventListener('submit', async (event) => {
  if (!event.submitter) return;
  event.preventDefault();
  const message = document.querySelector('#form-message');
  const fields = new URLSearchParams(new FormData(draft, event.submitter));
  const buttons = [...draft.querySelectorAll('button')];
  buttons.forEach((button) => { button.disabled = true; });
  try {
    const response = await fetch(draft.getAttribute('action'), { method: 'POST', body: fields });
    if (response.redirected && response.ok) {
      location.assign(response.url);
      return;
    }
    message.textContent = response.status === 404
      ? 'Your session has expired. Reopen the invitation in another tab before retrying.'
      : await response.text();
  } catch {
    message.textContent = 'Your changes have not been saved. Check your connection and try again.';
  }
  buttons.forEach((button) => { button.disabled = false; });
  message.hidden = false;
  message.focus();
});
