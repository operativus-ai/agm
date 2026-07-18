import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { AuthApi } from '../api/auth-api';

export const ForgotPasswordPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await AuthApi.requestPasswordReset({ email });
      // Always show the same confirmation — never reveal whether the email was known.
      setSubmitted(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-base-200">
      <div className="card w-96 bg-base-100 shadow-xl">
        <div className="card-body">
          <h2 className="card-title justify-center mb-4">Reset your password</h2>

          {submitted ? (
            <>
              <div className="alert alert-success text-sm py-2">
                If an account exists for that email, we've sent a link to reset your password. The
                link expires shortly — check your inbox (and spam).
              </div>
              <div className="text-center mt-4 text-sm">
                <Link to="/login" className="link link-primary">Back to login</Link>
              </div>
            </>
          ) : (
            <>
              <p className="text-sm text-center text-base-content/70 mb-4">
                Enter your account email and we'll send you a link to choose a new password.
              </p>
              {error && <div className="alert alert-error text-sm py-2 mb-4">{error}</div>}

              <form onSubmit={handleSubmit}>
                <div className="form-control w-full">
                  <label className="label">
                    <span className="label-text">Email</span>
                  </label>
                  <input
                    type="email"
                    placeholder="you@example.com"
                    className="input input-bordered w-full"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    required
                  />
                </div>

                <div className="card-actions justify-end mt-6">
                  <button type="submit" className="btn btn-primary w-full" disabled={submitting}>
                    {submitting ? 'Sending…' : 'Send reset link'}
                  </button>
                </div>
              </form>

              <div className="text-center mt-4 text-sm">
                Remembered it? <Link to="/login" className="link link-primary">Back to login</Link>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
