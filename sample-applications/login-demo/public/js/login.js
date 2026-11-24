// Login page logic.
const form = document.getElementById('login-form');
const banner = document.getElementById('login-error');

function validate() {
  let ok = true;
  const email = form.email.value.trim();
  document.getElementById('email-error').textContent = '';
  document.getElementById('password-error').textContent = '';
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
    document.getElementById('email-error').textContent = 'Enter a valid email address';
    ok = false;
  }
  if (!form.password.value) {
    document.getElementById('password-error').textContent = 'Password is required';
    ok = false;
  }
  return ok;
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  banner.hidden = true;
  if (!validate()) return;
  const response = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: form.email.value.trim(), password: form.password.value })
  });
  if (!response.ok) {
    const data = await response.json();
    banner.textContent = data.error;
    banner.hidden = false;
    console.warn('Login failed with status ' + response.status);
    return;
  }
  const data = await response.json();
  sessionStorage.setItem('token', data.token);
  location.href = '/dashboard';
});
