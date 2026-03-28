import { useState } from 'react';
import { Database, Lock, User, Loader2, ArrowRight, UserPlus } from 'lucide-react';
import axios from 'axios';

interface LoginProps {
  onLoginSuccess: (user: { username: string; role: 'student' | 'teacher' | 'admin' }) => void;
}

export function Login({ onLoginSuccess }: LoginProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [registerRole, setRegisterRole] = useState<'student' | 'teacher'>('student');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username || !password) return;

    setIsLoading(true);
    setError(null);

    try {
      const payload = {
        username,
        password,
        ...(mode === 'register' ? { role: registerRole } : {}),
      };
      const response = await axios.post(mode === 'login' ? '/api/auth/login' : '/api/auth/register', payload);

      if (response.data.success) {
        onLoginSuccess({
          username: response.data.username,
          role: response.data.role,
        });
      } else {
        setError(response.data.message || (mode === 'login' ? '登录失败，请检查账号密码' : '注册失败，请检查输入'));
      }
    } catch (err) {
      console.error('Login error:', err);
      setError('服务器连接失败，请稍后再试');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-6 font-sans">
      <div className="w-full max-w-md">
        {/* Logo & Header */}
        <div className="text-center mb-10">
          <div className="w-20 h-20 bg-slate-900 rounded-3xl mx-auto flex items-center justify-center text-white shadow-2xl mb-6">
            <Database size={40} />
          </div>
          <h1 className="text-4xl font-black tracking-tight text-slate-900 mb-2">
            CDAME <span className="text-slate-400 font-light">|</span> {mode === 'login' ? '登录' : '注册'}
          </h1>
          <p className="text-slate-500 font-bold">高校数字校史记忆引擎系统</p>
        </div>

        <div className="bg-white p-10 rounded-[2.5rem] shadow-xl border border-slate-100">
          <div className="grid grid-cols-2 gap-2 bg-slate-50 rounded-2xl p-1 mb-8">
            <button
              type="button"
              onClick={() => {
                setMode('login');
                setError(null);
              }}
              className={`h-12 rounded-xl text-sm font-black transition-all ${mode === 'login' ? 'bg-slate-900 text-white' : 'text-slate-500'}`}
            >
              登录
            </button>
            <button
              type="button"
              onClick={() => {
                setMode('register');
                setError(null);
              }}
              className={`h-12 rounded-xl text-sm font-black transition-all ${mode === 'register' ? 'bg-slate-900 text-white' : 'text-slate-500'}`}
            >
              注册
            </button>
          </div>

          <form onSubmit={handleSubmit} className="space-y-6">
            <div className="space-y-2">
              <label className="text-sm font-black text-slate-400 uppercase tracking-widest ml-1">
                账号名称
              </label>
              <div className="relative group">
                <div className="absolute left-5 top-1/2 -translate-y-1/2 text-slate-400 group-focus-within:text-slate-900 transition-colors">
                  <User size={20} />
                </div>
                <input
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="请输入账号"
                  className="w-full h-16 pl-14 pr-6 bg-slate-50 border-2 border-transparent rounded-2xl focus:bg-white focus:border-slate-900 focus:outline-none text-lg font-bold transition-all"
                  required
                />
              </div>
            </div>

            <div className="space-y-2">
              <label className="text-sm font-black text-slate-400 uppercase tracking-widest ml-1">
                登录密码
              </label>
              <div className="relative group">
                <div className="absolute left-5 top-1/2 -translate-y-1/2 text-slate-400 group-focus-within:text-slate-900 transition-colors">
                  <Lock size={20} />
                </div>
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={mode === 'register' ? '请输入密码（至少6位）' : '请输入密码'}
                  className="w-full h-16 pl-14 pr-6 bg-slate-50 border-2 border-transparent rounded-2xl focus:bg-white focus:border-slate-900 focus:outline-none text-lg font-bold transition-all"
                  required
                />
              </div>
            </div>

            {mode === 'register' && (
              <div className="space-y-2">
                <label className="text-sm font-black text-slate-400 uppercase tracking-widest ml-1">
                  注册角色
                </label>
                <select
                  value={registerRole}
                  onChange={(e) => setRegisterRole(e.target.value as 'student' | 'teacher')}
                  className="w-full h-14 px-5 bg-slate-50 border-2 border-transparent rounded-2xl focus:bg-white focus:border-slate-900 focus:outline-none text-base font-bold transition-all"
                >
                  <option value="student">学生</option>
                  <option value="teacher">教师</option>
                </select>
              </div>
            )}

            {error && (
              <div className="bg-rose-50 text-rose-600 p-4 rounded-xl text-sm font-bold border border-rose-100 text-center animate-shake">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={isLoading || !username || !password}
              className="w-full h-16 bg-slate-900 text-white rounded-2xl font-black text-xl flex items-center justify-center gap-3 hover:bg-slate-800 active:scale-[0.98] transition-all disabled:opacity-50 shadow-lg shadow-slate-200"
            >
              {isLoading ? (
                <Loader2 size={24} className="animate-spin" />
              ) : (
                <>
                  <span>{mode === 'login' ? '立即进入系统' : '创建账号并登录'}</span>
                  {mode === 'login' ? <ArrowRight size={24} /> : <UserPlus size={24} />}
                </>
              )}
            </button>
          </form>

          <div className="mt-10 pt-8 border-t border-slate-50">
            <p className="text-xs font-black text-slate-300 uppercase tracking-widest text-center mb-4">
              虚拟测试账号
            </p>
            <div className="flex justify-between gap-4">
              <div className="flex-1 p-3 bg-slate-50 rounded-xl text-center">
                <p className="text-[10px] font-black text-slate-400 uppercase">学生</p>
                <p className="text-xs font-bold text-slate-600">student / student123</p>
              </div>
              <div className="flex-1 p-3 bg-slate-50 rounded-xl text-center">
                <p className="text-[10px] font-black text-slate-400 uppercase">老师</p>
                <p className="text-xs font-bold text-slate-600">teacher / teacher123</p>
              </div>
              <div className="flex-1 p-3 bg-slate-50 rounded-xl text-center">
                <p className="text-[10px] font-black text-slate-400 uppercase">管理员</p>
                <p className="text-xs font-bold text-slate-600">admin / admin123</p>
              </div>
            </div>
          </div>
        </div>

        <p className="text-center mt-12 text-[10px] font-black text-slate-300 uppercase tracking-[0.4em]">
          Campus Digital AI Memory Engine · Securing Identity
        </p>
      </div>
    </div>
  );
}
