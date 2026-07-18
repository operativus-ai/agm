import React, { useState } from 'react';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import { AuthApi } from '../api/auth-api';

export const ResetPasswordPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const navigate = useNavigate();

  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [done, setDone] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (password.length < 8 || password.length > 128) {
      setError('Password must be between 8 and 128 characters.');
      return;
    }
    if (password !== confirm) {
      setError('Passwords do not match.');
      return;
    }
    setSubmitting(true);
    try {
      await AuthApi.confirmPasswordReset({ token, newPassword: password });
      setDone(true);
    } catch {
      // BE returns a uniform 400 for invalid/expired/used token or weak password.
      setError('This reset link is invalid or has expired. Please request a new one.');
    } finally {
      setSubmitting(false);
    }
  };

  const missingToken = !token;

  return (
    <div className="min-h-screen flex items-center justify-center bg-base-200">
      <div className="card w-96 bg-base-100 shadow-xl">
        <div className="card-body">
          <h2 className="card-title justify-center mb-4">Choose a new password</h2>

          {done ? (
            <>
              <div className="alert alert-success text-sm py-2">
                Your password has been reset. You can now log in with your new password.
              </div>
              <div className="card-actions justify-end mt-6">
                <button className="btn btn-primary w-full" onClick={() => navigate('/login')}>
                  Go to login
                </button>
              </div>
            </>
          ) : missingToken ? (
            <>
              <div className="alert alert-error text-sm py-2">
                This page needs a valid reset link. Request a new one to continue.
              </div>
              <div className="text-center mt-4 text-sm">
                <Link to="/forgot-password" className="link link-primary">Request a reset link</Link>
              </div>
            </>
          ) : (
            <>
              {error && <div className="alert alert-error text-sm py-2 mb-4">{error}</div>}

              <form onSubmit={handleSubmit}>
                <div className="form-control w-full">
                  <label className="label">
                    <span className="label-text">New password</span>
                  </label>
                  <input
                    type="password"
                    placeholder="new password"
                    className="input input-bordered w-full"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    minLength={8}
                    maxLength={128}
                    required
                  />
                  <span className="label-text-alt text-base-content/60 mt-1">8–128 characters.</span>
                </div>

                <div className="form-control w-full mt-4">
                  <label className="label">
                    <span className="label-text">Confirm new password</span>
                  </label>
                  <input
                    type="password"
                    placeholder="confirm password"
                    className="input input-bordered w-full"
                    value={confirm}
                    onChange={(e) => setConfirm(e.target.value)}
                    required
                  />
                </div>

                <div className="card-actions justify-end mt-6">
                  <button type="submit" className="btn btn-primary w-full" disabled={submitting}>
                    {submitting ? 'Resetting…' : 'Reset password'}
                  </button>
                </div>
              </form>

              <div className="text-center mt-4 text-sm">
                <Link to="/login" className="link link-primary">Back to login</Link>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
