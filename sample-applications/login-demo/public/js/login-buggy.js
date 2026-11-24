// Login page logic (DEMO_BUG=login): posts to the wrong endpoint and swallows the failure.
const form = document.getElementById('login-form');

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const response = await fetch('/api/signin', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: form.email.value.trim(), password: form.password.value })
  });
  const data = await response.json();
  sessionStorage.setItem('token', data.token);
  if (data.token) location.href = '/dashboard';
  else console.error('Login failed: no token in response');
});
