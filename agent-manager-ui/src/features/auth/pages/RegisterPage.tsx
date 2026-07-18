import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export const RegisterPage: React.FC = () => {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const { register } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      await register({ username, email, password });
      navigate('/login');
    } catch (err: any) {
      setError(err.message || 'Failed to register');
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-base-200">
        <div className="card w-96 bg-base-100 shadow-xl">
            <div className="card-body">
                <h2 className="card-title justify-center mb-4">Register</h2>
                {error && <div className="alert alert-error text-sm py-2 mb-4">{error}</div>}
                
                <form onSubmit={handleSubmit}>
                    <div className="form-control w-full">
                        <label className="label">
                            <span className="label-text">Username</span>
                        </label>
                        <input 
                            type="text" 
                            placeholder="username" 
                            className="input input-bordered w-full" 
                            value={username}
                            onChange={(e) => setUsername(e.target.value)}
                            required
                        />
                    </div>

                    <div className="form-control w-full mt-4">
                        <label className="label">
                            <span className="label-text">Email</span>
                        </label>
                        <input 
                            type="email" 
                            placeholder="email@example.com" 
                            className="input input-bordered w-full" 
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                            required
                        />
                    </div>

                    <div className="form-control w-full mt-4">
                        <label className="label">
                            <span className="label-text">Password</span>
                        </label>
                        <input 
                            type="password" 
                            placeholder="password" 
                            className="input input-bordered w-full" 
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            required
                        />
                    </div>

                    <div className="card-actions justify-end mt-6">
                        <button type="submit" className="btn btn-primary w-full">Register</button>
                    </div>
                </form>

                <div className="text-center mt-4 text-sm">
                    Already have an account? <Link to="/login" className="link link-primary">Login</Link>
                </div>
            </div>
        </div>
    </div>
  );
};
