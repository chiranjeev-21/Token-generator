// src/App.tsx
import { useState, useRef, useEffect, useCallback } from 'react';
import axios from 'axios';

type Stage = 'email' | 'otp' | 'token';
type ValidationState = 'idle' | 'checking' | 'valid' | 'invalid';

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '/api/v1/token').replace(/\/+$/, '');
const api = axios.create({ baseURL: apiBaseUrl });

// ─────────────────────────────────────────────────────────────────────────────
// Personal email providers — ALWAYS blocked, no flag can override this.
// These domains are free consumer services, not company-issued emails.
// ─────────────────────────────────────────────────────────────────────────────
const PERSONAL_DOMAINS = new Set([
  'gmail.com', 'googlemail.com',
  'hotmail.com', 'hotmail.in', 'hotmail.co.uk', 'hotmail.fr', 'hotmail.de',
  'outlook.com', 'outlook.in', 'outlook.co.uk', 'live.com', 'live.in',
  'live.co.uk', 'msn.com',
  'icloud.com', 'me.com', 'mac.com',
  'yahoo.com', 'yahoo.in', 'yahoo.co.in', 'yahoo.co.uk', 'yahoo.fr',
  'yahoo.de', 'yahoo.co.jp', 'ymail.com',
  'aol.com', 'verizon.net',
  'protonmail.com', 'protonmail.ch', 'proton.me', 'tutanota.com',
  'tutanota.de', 'tutamail.com', 'pm.me',
  'rediffmail.com',
  'yandex.com', 'yandex.ru', 'yandex.ua', 'mail.ru', 'bk.ru', 'list.ru',
  'mail.com', 'gmx.com', 'gmx.net', 'gmx.de', 'zoho.com',
  'inbox.com', 'lycos.com', 'fastmail.com', 'hushmail.com',
]);

const DISPOSABLE_DOMAINS = new Set([
  'mailinator.com', 'guerrillamail.com', 'guerrillamailblock.com',
  'tempmail.com', 'temp-mail.org', 'temp-mail.io', 'tempr.email',
  'yopmail.com', 'yopmail.fr', 'trashmail.com', 'trashmail.at',
  'trashmail.me', 'trashmail.io', 'trashmail.xyz', 'maildrop.cc',
  'fakeinbox.com', 'throwaway.email', 'discard.email', 'mailnull.com',
  'getnada.com', 'getairmail.com', 'spamgap.com', 'spamherelots.com',
  'spam4.me', 'spamfree24.org', 'spambox.us', 'spamevader.com',
  '10minutemail.com', '10minutemail.net', '20minutemail.com',
  'minutemail.com', 'tempmailo.com', 'sharklasers.com', 'guerrillamail.info',
  'filzmail.com', 'mailnesia.com', 'dispostable.com',
]);

/**
 * Instant check — runs synchronously on every keystroke.
 * Personal and disposable domains are ALWAYS rejected here.
 * No async, no flags, no race conditions.
 */
function instantCheck(email: string): string | null {
  const lower = email.trim().toLowerCase();
  if (!lower.includes('@')) return null;

  const domain = lower.slice(lower.lastIndexOf('@') + 1);
  if (!domain || !domain.includes('.')) return null; // still typing the domain

  if (DISPOSABLE_DOMAINS.has(domain)) {
    return 'Disposable / temporary email addresses are not allowed.';
  }

  if (PERSONAL_DOMAINS.has(domain)) {
    return `"@${domain}" is a personal email provider and is not accepted. Please use your company email — e.g. you@amazon.com, you@microsoft.com.`;
  }

  return null; // passes instant check — MX lookup still runs onBlur
}

interface ClientInfo {
  id: string;
  name: string;
  description: string;
  requireWorkEmail: boolean;
  tokenTtlHours: number;
}

const DEFAULT_CLIENT: ClientInfo = {
  id: 'default',
  name: 'App',
  description: 'Verify your company email to receive a one-time access token.',
  requireWorkEmail: true, // safe default — personal emails always blocked
  tokenTtlHours: 24,
};

export default function App() {
  const clientId = new URLSearchParams(window.location.search).get('app') ?? '';

  const [clientInfo, setClientInfo]           = useState<ClientInfo>(DEFAULT_CLIENT);
  const [clientLoading, setClientLoading]     = useState(!!clientId);

  const [stage, setStage]                     = useState<Stage>('email');
  const [email, setEmail]                     = useState('');
  const [emailError, setEmailError]           = useState('');
  const [validationState, setValidationState] = useState<ValidationState>('idle');

  const [otp, setOtp]         = useState(['', '', '', '', '', '']);
  const [token, setToken]     = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState('');
  const [copied, setCopied]   = useState(false);
  const otpRefs = useRef<(HTMLInputElement | null)[]>([]);

  // ── Load client info ────────────────────────────────────────────────────────
  useEffect(() => {
    if (!clientId) { setClientLoading(false); return; }
    api.get(`/client/${clientId}`)
      .then(r => { setClientInfo(r.data); setClientLoading(false); })
      .catch(() => setClientLoading(false));
  }, [clientId]);

  useEffect(() => {
    if (clientInfo.name !== 'App') document.title = `Verify — ${clientInfo.name}`;
  }, [clientInfo]);

  // ── Email change: instant check on every keystroke ─────────────────────────
  function handleEmailChange(val: string) {
    setEmail(val);
    setError('');

    // Reset server validation when user edits
    if (validationState === 'valid' || validationState === 'invalid') {
      setValidationState('idle');
    }

    const err = instantCheck(val); // no flags, no async, always correct
    if (err) {
      setEmailError(err);
      setValidationState('invalid');
    } else {
      setEmailError('');
      if (validationState === 'invalid') setValidationState('idle');
    }
  }

  // ── onBlur: MX record check via server ────────────────────────────────────
  const handleEmailBlur = useCallback(async () => {
    const domain = email.slice(email.lastIndexOf('@') + 1);
    if (!email.includes('@') || !domain.includes('.')) return;

    // Don't call server if already blocked locally
    const instant = instantCheck(email);
    if (instant) {
      setEmailError(instant);
      setValidationState('invalid');
      return;
    }

    setValidationState('checking');
    setEmailError('');
    try {
      await api.post('/validate-email', {
        email: email.trim(),
        clientId: clientInfo.id,
      });
      setValidationState('valid');
    } catch (err: any) {
      const msg = err?.response?.data?.message
        ?? 'This email address could not be verified. Please check for typos.';
      setEmailError(msg);
      setValidationState('invalid');
    }
  }, [email, clientInfo]);

  // ── Submit: gate on valid state ────────────────────────────────────────────
  async function requestOtp(e: React.FormEvent) {
    e.preventDefault();

    // Run checks if user submitted without blurring
    const instant = instantCheck(email);
    if (instant) {
      setEmailError(instant);
      setValidationState('invalid');
      return;
    }

    if (validationState !== 'valid') {
      // Force the MX check synchronously
      setValidationState('checking');
      try {
        await api.post('/validate-email', { email: email.trim(), clientId: clientInfo.id });
        setValidationState('valid');
      } catch (err: any) {
        const msg = err?.response?.data?.message ?? 'Email could not be verified.';
        setEmailError(msg);
        setValidationState('invalid');
        return;
      }
    }

    setLoading(true); setError('');
    try {
      await api.post('/request-otp', { email: email.trim(), clientId: clientInfo.id });
      setStage('otp');
    } catch (err: any) {
      const msg = err?.response?.data?.message ?? err?.response?.data?.detail
        ?? 'Failed to send OTP. Please try again.';
      setEmailError(msg);
      setValidationState('invalid');
    } finally { setLoading(false); }
  }

  // ── Verify OTP ─────────────────────────────────────────────────────────────
  async function verifyOtp(e: React.FormEvent) {
    e.preventDefault();
    const code = otp.join('');
    if (code.length < 6) { setError('Enter the complete 6-digit code.'); return; }
    setLoading(true); setError('');
    try {
      const res = await api.post('/verify-otp', {
        email: email.trim(), otp: code, clientId: clientInfo.id,
      });
      setToken(res.data.data.token);
      setStage('token');
    } catch (err: any) {
      setError(err?.response?.data?.message ?? 'Invalid or expired OTP.');
    } finally { setLoading(false); }
  }

  function handleOtpChange(i: number, val: string) {
    if (!/^\d*$/.test(val)) return;
    const next = [...otp]; next[i] = val.slice(-1); setOtp(next);
    if (val && i < 5) otpRefs.current[i + 1]?.focus();
  }
  function handleOtpKey(i: number, e: React.KeyboardEvent) {
    if (e.key === 'Backspace' && !otp[i] && i > 0) otpRefs.current[i - 1]?.focus();
  }
  function handleOtpPaste(e: React.ClipboardEvent) {
    const paste = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (paste.length === 6) { setOtp(paste.split('')); otpRefs.current[5]?.focus(); }
  }

  async function copyToken() {
    await navigator.clipboard.writeText(token);
    setCopied(true); setTimeout(() => setCopied(false), 2500);
  }

  function reset() {
    setStage('email'); setEmail(''); setOtp(['','','','','','']);
    setToken(''); setError(''); setEmailError(''); setValidationState('idle');
  }

  // ── Derived state ──────────────────────────────────────────────────────────
  const isInvalid  = validationState === 'invalid' || !!emailError;
  const isChecking = validationState === 'checking';
  const isValid    = validationState === 'valid';
  const submitDisabled = loading || isInvalid || isChecking || !email.trim();

  const inputBorder = isInvalid  ? '#f43f5e'
                    : isValid    ? 'rgba(16,185,129,0.6)'
                    : isChecking ? 'rgba(245,158,11,0.6)'
                    : 'var(--accent)';

  if (clientLoading) {
    return (
      <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ display: 'inline-block', width: 28, height: 28, border: '2px solid rgba(255,255,255,0.1)', borderTopColor: 'var(--indigo)', borderRadius: '50%', animation: 'spin 0.7s linear infinite' }} />
      </div>
    );
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '32px 20px', position: 'relative', overflow: 'hidden' }}>
      <div style={{ position: 'fixed', inset: 0, pointerEvents: 'none', zIndex: 0, background: 'radial-gradient(ellipse 800px 500px at 20% 30%, rgba(99,102,241,0.05) 0%, transparent 60%), radial-gradient(ellipse 600px 400px at 80% 70%, rgba(139,92,246,0.04) 0%, transparent 60%)' }} />
      <div style={{ position: 'fixed', inset: 0, pointerEvents: 'none', zIndex: 0, opacity: 0.025, backgroundImage: 'linear-gradient(rgba(255,255,255,0.5) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.5) 1px, transparent 1px)', backgroundSize: '60px 60px' }} />

      <div style={{ position: 'relative', zIndex: 1, width: '100%', maxWidth: 460, animation: 'fadeUp 0.5s ease both' }}>

        {/* Header */}
        <div style={{ textAlign: 'center', marginBottom: 40 }}>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 10, marginBottom: 24 }}>
            <span style={{ fontSize: 28, color: 'var(--indigo-l)', lineHeight: 1 }}>◈</span>
            <span style={{ fontFamily: 'var(--display)', fontSize: 18, fontWeight: 700, color: 'var(--txt-1)' }}>
              {clientInfo.name}
            </span>
          </div>
          <h1 style={{ fontFamily: 'var(--display)', fontSize: 'clamp(28px, 5vw, 40px)', fontWeight: 800, letterSpacing: '-1.5px', lineHeight: 1.1, marginBottom: 12 }}>
            Verify your<br />
            <span style={{ background: 'linear-gradient(135deg, var(--indigo-l) 0%, var(--violet) 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>
              company email
            </span>
          </h1>
          <p style={{ fontSize: 15, color: 'var(--txt-2)', lineHeight: 1.65, maxWidth: 360, margin: '0 auto' }}>
            {clientInfo.description}
          </p>
        </div>

        {/* Progress dots */}
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginBottom: 32 }}>
          {(['email', 'otp', 'token'] as Stage[]).map(s => (
            <div key={s} style={{ width: s === stage ? 24 : 8, height: 8, borderRadius: 999, background: s === stage ? 'var(--indigo)' : stage === 'token' && s !== 'token' ? 'var(--indigo)' : 'var(--elevated)', transition: 'all 0.3s ease' }} />
          ))}
        </div>

        {/* Card */}
        <div style={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 'var(--r-xl)', padding: '36px', boxShadow: '0 4px 40px rgba(0,0,0,0.4)' }}>

          {/* ── Email stage ── */}
          {stage === 'email' && (
            <form onSubmit={requestOtp}>
              <label style={labelS}>Company email address</label>

              <div style={{ position: 'relative', marginBottom: 0 }}>
                <input
                  type="email"
                  value={email}
                  onChange={e => handleEmailChange(e.target.value)}
                  onBlur={handleEmailBlur}
                  placeholder="you@yourcompany.com"
                  autoFocus
                  style={{ ...inputS, borderColor: inputBorder, marginBottom: 0, paddingRight: 44 }}
                />
                <div style={{ position: 'absolute', right: 14, top: '50%', transform: 'translateY(-50%)' }}>
                  {isChecking && <span style={{ display: 'inline-block', width: 14, height: 14, border: '2px solid rgba(245,158,11,0.3)', borderTopColor: 'var(--amber)', borderRadius: '50%', animation: 'spin 0.7s linear infinite' }} />}
                  {isValid    && <span style={{ fontSize: 14, color: 'var(--emerald)' }}>✓</span>}
                  {isInvalid  && <span style={{ fontSize: 14, color: 'var(--rose)' }}>✕</span>}
                </div>
              </div>

              {/* Status message */}
              <div style={{ minHeight: 44, marginTop: 8, marginBottom: 12 }}>
                {isInvalid && emailError && (
                  <div style={{ display: 'flex', alignItems: 'flex-start', gap: 7, fontSize: 13, color: 'var(--rose)', lineHeight: 1.55 }}>
                    <span style={{ flexShrink: 0, fontWeight: 700 }}>✕</span>
                    {emailError}
                  </div>
                )}
                {isChecking && (
                  <p style={{ fontSize: 12, color: 'var(--amber)', fontFamily: 'var(--mono)', margin: 0 }}>
                    Verifying email address…
                  </p>
                )}
                {isValid && (
                  <p style={{ fontSize: 12, color: 'var(--emerald)', fontFamily: 'var(--mono)', margin: 0 }}>
                    ✓ Email address verified
                  </p>
                )}
                {validationState === 'idle' && !emailError && (
                  <p style={{ fontSize: 12, color: 'var(--txt-3)', margin: 0, lineHeight: 1.55 }}>
                    Only company emails are accepted — e.g. you@amazon.com, you@microsoft.com.
                    Gmail, Outlook and other personal providers are not allowed.
                  </p>
                )}
              </div>

              {error && <ErrorBox msg={error} />}

              <button type="submit" disabled={submitDisabled} style={primaryS(submitDisabled)}>
                {loading ? <Spinner /> : isChecking ? 'Verifying…' : 'Send verification code →'}
              </button>
            </form>
          )}

          {/* ── OTP stage ── */}
          {stage === 'otp' && (
            <form onSubmit={verifyOtp}>
              <p style={{ fontSize: 14, color: 'var(--txt-2)', marginBottom: 24, lineHeight: 1.6 }}>
                We sent a 6-digit code to{' '}
                <span style={{ color: 'var(--indigo-l)', fontFamily: 'var(--mono)', fontWeight: 500 }}>{email}</span>.
                Check your inbox (and spam folder).
              </p>
              <label style={labelS}>Verification code</label>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'center', marginBottom: 24 }}>
                {otp.map((digit, i) => (
                  <input key={i} ref={el => otpRefs.current[i] = el}
                    type="text" inputMode="numeric" maxLength={1} value={digit}
                    onChange={e => handleOtpChange(i, e.target.value)}
                    onKeyDown={e => handleOtpKey(i, e)}
                    onPaste={handleOtpPaste}
                    autoFocus={i === 0}
                    style={{ width: 52, height: 60, textAlign: 'center', fontSize: 24, fontWeight: 700, fontFamily: 'var(--mono)', background: digit ? 'rgba(99,102,241,0.1)' : 'var(--elevated)', border: `2px solid ${digit ? 'var(--indigo)' : 'var(--accent)'}`, borderRadius: 'var(--r-md)', color: 'var(--txt-1)', outline: 'none', transition: 'all 0.15s' }}
                    onFocus={e2 => e2.target.style.borderColor = 'var(--indigo-l)'}
                    onBlur={e2  => e2.target.style.borderColor = digit ? 'var(--indigo)' : 'var(--accent)'}
                  />
                ))}
              </div>
              {error && <ErrorBox msg={error} />}
              <button type="submit" disabled={loading || otp.join('').length < 6} style={primaryS(loading || otp.join('').length < 6)}>
                {loading ? <Spinner /> : 'Verify & get token →'}
              </button>
              <button type="button"
                onClick={() => { setStage('email'); setOtp(['','','','','','']); setError(''); }}
                style={{ display: 'block', textAlign: 'center', width: '100%', marginTop: 14, background: 'none', border: 'none', color: 'var(--txt-3)', fontSize: 13, cursor: 'pointer', transition: 'color 0.15s' }}
                onMouseEnter={e2 => (e2.currentTarget).style.color = 'var(--txt-2)'}
                onMouseLeave={e2 => (e2.currentTarget).style.color = 'var(--txt-3)'}>
                ← Change email or resend code
              </button>
            </form>
          )}

          {/* ── Token stage ── */}
          {stage === 'token' && (
            <div style={{ animation: 'fadeUp 0.4s ease both' }}>
              <div style={{ textAlign: 'center', marginBottom: 24 }}>
                <div style={{ width: 56, height: 56, borderRadius: '50%', background: 'rgba(16,185,129,0.12)', border: '2px solid rgba(16,185,129,0.3)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 16px', fontSize: 26 }}>✓</div>
                <h2 style={{ fontFamily: 'var(--display)', fontSize: 22, fontWeight: 800, marginBottom: 6 }}>Email Verified!</h2>
                <p style={{ fontSize: 14, color: 'var(--txt-2)' }}>Your one-time token for <strong>{clientInfo.name}</strong> is ready.</p>
              </div>
              <div style={{ background: 'var(--elevated)', border: '1px solid var(--accent)', borderRadius: 'var(--r-md)', padding: '16px', marginBottom: 16 }}>
                <p style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--txt-2)', wordBreak: 'break-all', lineHeight: 1.7, userSelect: 'all' }}>
                  {token}
                </p>
              </div>
              <button onClick={copyToken} style={{ ...primaryS(false), background: copied ? 'rgba(16,185,129,0.2)' : 'var(--indigo)', border: copied ? '1px solid rgba(16,185,129,0.4)' : 'none', color: copied ? 'var(--emerald)' : '#fff', transition: 'all 0.2s' }}>
                {copied ? '✓ Copied to clipboard!' : '⎘ Copy token'}
              </button>
              <div style={{ marginTop: 20, background: 'rgba(99,102,241,0.07)', border: '1px solid rgba(99,102,241,0.15)', borderRadius: 'var(--r-md)', padding: '14px 16px' }}>
                <p style={{ fontSize: 12, color: 'var(--txt-3)', fontFamily: 'var(--mono)', lineHeight: 1.7 }}>
                  ⏱ Valid for {clientInfo.tokenTtlHours}h &nbsp;·&nbsp; ⚠ Single use only &nbsp;·&nbsp; 🔒 Cryptographically signed
                </p>
              </div>
              <button onClick={reset} style={{ display: 'block', textAlign: 'center', width: '100%', marginTop: 16, background: 'none', border: 'none', color: 'var(--txt-3)', fontSize: 13, cursor: 'pointer', transition: 'color 0.15s' }}
                onMouseEnter={e2 => (e2.currentTarget).style.color = 'var(--txt-2)'}
                onMouseLeave={e2 => (e2.currentTarget).style.color = 'var(--txt-3)'}>
                Get another token
              </button>
            </div>
          )}
        </div>

        <p style={{ textAlign: 'center', marginTop: 24, fontSize: 12, color: 'var(--txt-3)', fontFamily: 'var(--mono)' }}>
          tokens are cryptographically signed · tamper-proof · single-use
        </p>
      </div>
    </div>
  );
}

function ErrorBox({ msg }: { msg: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)', borderRadius: 8, padding: '11px 14px', marginBottom: 16, fontSize: 13, color: 'var(--rose)', lineHeight: 1.5 }}>
      ⚠ {msg}
    </div>
  );
}

function Spinner() {
  return <span style={{ display: 'inline-block', width: 16, height: 16, border: '2px solid rgba(255,255,255,0.2)', borderTopColor: '#fff', borderRadius: '50%', animation: 'spin 0.7s linear infinite' }} />;
}

const labelS: React.CSSProperties = {
  display: 'block', fontSize: 11, fontFamily: 'var(--mono)',
  color: 'var(--txt-3)', letterSpacing: '0.07em', textTransform: 'uppercase', marginBottom: 8,
};
const inputS: React.CSSProperties = {
  width: '100%', background: 'var(--elevated)',
  border: '2px solid var(--accent)', borderRadius: 'var(--r-md)',
  padding: '12px 16px', color: 'var(--txt-1)', fontSize: 15,
  fontFamily: 'var(--body)', outline: 'none', marginBottom: 18, transition: 'border-color 0.2s',
};
const primaryS = (disabled: boolean): React.CSSProperties => ({
  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
  width: '100%', background: disabled ? 'var(--elevated)' : 'var(--indigo)',
  border: 'none', borderRadius: 'var(--r-md)', padding: '14px',
  color: disabled ? 'var(--txt-3)' : '#fff',
  fontSize: 15, fontWeight: 600, cursor: disabled ? 'not-allowed' : 'pointer',
  transition: 'background 0.15s', opacity: disabled ? 0.7 : 1,
});
