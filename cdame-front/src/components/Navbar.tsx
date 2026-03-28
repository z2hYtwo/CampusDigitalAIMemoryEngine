import React, { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  Search,
  BookOpen,
  User,
  LogOut,
  Menu,
  X,
  ChevronDown,
  Database,
  History,
  Award,
  FileText,
  PieChart,
  Shield,
  Loader2,
  Upload,
  Link as LinkIcon,
  GraduationCap
} from 'lucide-react';
import { cn } from '../utils/cn';

interface NavItem {
  label: string;
  icon: React.ReactNode;
  path?: string;
  children?: { label: string; path: string; icon: React.ReactNode }[];
}

interface NavbarProps {
  user: { username: string; role: string } | null;
  onLogout: () => void;
  onLogin?: () => void;
  // 新增管理功能的 props
  onSync?: () => void;
  onUpload?: (e: React.ChangeEvent<HTMLInputElement>) => void;
  onAddLink?: () => void;
  isSyncing?: boolean;
  isUploading?: boolean;
}

export function Navbar({ 
  user, 
  onLogout, 
  onLogin,
  onSync, 
  onUpload, 
  onAddLink, 
  isSyncing, 
  isUploading 
}: NavbarProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [activeSubmenu, setActiveSubmenu] = useState<string | null>(null);
  const [showAdminMenu, setShowAdminMenu] = useState(false);
  const location = useLocation();
  const adminMenuRef = useRef<HTMLDivElement | null>(null);
  const uploadInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (!adminMenuRef.current) return;
      const target = event.target as Node | null;
      if (target && !adminMenuRef.current.contains(target)) {
        setShowAdminMenu(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const isAdminOrTeacher = user?.role === 'admin' || user?.role === 'teacher';

  const navItems: NavItem[] = [
    { label: '智能问答', icon: <Search size={20} />, path: '/' },
    ...(user ? [{
      label: '校史记忆',
      icon: <History size={20} />,
      children: [
        { label: '档案库', path: '/history/archives', icon: <Database size={18} /> },
        { label: '多媒体', path: '/history/media', icon: <FileText size={18} /> },
      ]
    }] : []),
    { label: '荣誉树', icon: <Award size={20} />, path: '/history/honor-wall' },
    ...(user ? [{
      label: '学业中心',
      icon: <BookOpen size={20} />,
      children: [
        { label: '成绩分析', path: '/academic/scores', icon: <PieChart size={18} /> },
        { label: '专业模板', path: '/academic/majors', icon: <FileText size={18} /> },
      ]
    }] : []),
    { label: '政策查询', icon: <Shield size={20} />, path: '/policies' },
    ...(!user ? [{ label: '专业介绍', icon: <GraduationCap size={20} />, path: '/majors' }] : []),
    ...(user ? [{ label: '私人空间', icon: <User size={20} />, path: '/private' }] : []),
  ];

  const toggleSubmenu = (label: string) => {
    setActiveSubmenu(activeSubmenu === label ? null : label);
  };

  return (
    <nav className="bg-white/80 backdrop-blur-2xl border-b border-slate-200 sticky top-0 z-[100] w-full shadow-sm">
      <div className="max-w-[1800px] mx-auto px-6 sm:px-10 lg:px-12">
        <div className="flex justify-between h-24 sm:h-28">
          <div className="flex items-center gap-6 xl:gap-12">
            {/* Logo */}
            <Link to="/" className="flex items-center gap-4 group shrink-0">
              <div className="bg-gradient-to-br from-blue-600 to-indigo-700 p-2.5 rounded-xl group-hover:rotate-6 transition-all duration-500 shadow-lg shadow-blue-200/50">
                <Database className="text-white" size={22} />
              </div>
              <div className="flex flex-col">
                <span className="text-3xl sm:text-4xl font-black bg-clip-text text-transparent bg-gradient-to-r from-blue-600 via-indigo-600 to-purple-600 tracking-tighter leading-none">
                  CDAME
                </span>
                <span className="text-xs sm:text-sm font-black text-slate-400 uppercase tracking-[0.22em] mt-0.5 sm:mt-1">
                  Memory Engine
                </span>
              </div>
            </Link>

            {/* Desktop Navigation */}
            <div className="hidden lg:flex lg:items-center lg:gap-2">
              {navItems.map((item) => (
                <div 
                  key={item.label} 
                  className="relative group py-2"
                  onMouseEnter={() => item.children && setActiveSubmenu(item.label)}
                  onMouseLeave={() => setActiveSubmenu(null)}
                >
                  {item.path ? (
                    <Link
                      to={item.path}
                      className={cn(
                        "flex items-center gap-3 px-6 py-3.5 rounded-xl text-lg font-black transition-all duration-300",
                        location.pathname === item.path 
                          ? "bg-slate-900 text-white shadow-lg shadow-slate-200" 
                          : "text-slate-600 hover:bg-slate-50 hover:text-blue-600"
                      )}
                    >
                      {item.icon}
                      {item.label}
                    </Link>
                  ) : (
                    <button
                      className={cn(
                        "flex items-center gap-3 px-6 py-3.5 rounded-xl text-lg font-black transition-all duration-300",
                        activeSubmenu === item.label || item.children?.some(c => location.pathname === c.path)
                          ? "bg-slate-100 text-blue-600" 
                          : "text-slate-600 hover:bg-slate-50 hover:text-blue-600"
                      )}
                    >
                      {item.icon}
                      {item.label}
                      <ChevronDown size={16} className={cn("transition-transform duration-300", activeSubmenu === item.label && "rotate-180")} />
                    </button>
                  )}

                  {/* Submenu Dropdown */}
                  {item.children && activeSubmenu === item.label && (
                    <div className="absolute top-full left-0 w-64 pt-2 animate-in fade-in slide-in-from-top-2 duration-200">
                      <div className="bg-white rounded-2xl border border-slate-100 shadow-xl shadow-slate-200/50 p-2 overflow-hidden">
                        {item.children.map((child) => (
                          <Link
                            key={child.path}
                            to={child.path}
                            className={cn(
                              "flex items-center gap-3 px-4 py-3.5 rounded-xl text-lg font-bold transition-all duration-200",
                              location.pathname === child.path
                                ? "bg-blue-50 text-blue-600"
                                : "text-slate-500 hover:bg-slate-50 hover:text-slate-900"
                            )}
                          >
                            <div className={cn(
                              "p-2.5 rounded-lg shrink-0",
                              location.pathname === child.path ? "bg-white shadow-sm" : "bg-slate-100"
                            )}>
                              {child.icon}
                            </div>
                            {child.label}
                          </Link>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* User Profile & Actions */}
          <div className="flex items-center gap-2 sm:gap-4">
            {user && (
              <div className="flex items-center gap-3 sm:gap-5 pl-5 sm:pl-7 border-l border-slate-100 h-12 sm:h-14">
                {/* 管理员/教师特有功能 - 整合进下拉菜单 */}
                {isAdminOrTeacher && (
                  <div className="relative" ref={adminMenuRef}>
                    <button
                      onClick={() => setShowAdminMenu(!showAdminMenu)}
                      className={cn(
                        "flex items-center gap-2 px-4 sm:px-5 py-3 rounded-xl text-lg font-black transition-all",
                        showAdminMenu || isSyncing || isUploading
                          ? "bg-slate-900 text-white" 
                          : "bg-blue-50 text-blue-600 hover:bg-blue-100"
                      )}
                    >
                      <Shield size={18} />
                      <span className="hidden sm:inline">管理面板</span>
                      <ChevronDown size={16} className={cn("transition-transform", showAdminMenu && "rotate-180")} />
                    </button>

                    {showAdminMenu && (
                      <div className="absolute top-full right-0 mt-2 w-64 bg-white rounded-2xl border border-slate-100 shadow-2xl p-2 z-[110] animate-in fade-in slide-in-from-top-2">
                        <div className="px-3 py-2 mb-1">
                          <p className="text-[10px] font-black text-slate-400 uppercase tracking-widest">系统管理操作</p>
                        </div>
                        
                        <button
                          onClick={onSync}
                          disabled={isSyncing}
                          className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-bold text-slate-700 hover:bg-slate-50 transition-colors disabled:opacity-50"
                        >
                          <div className="p-1.5 bg-blue-50 text-blue-600 rounded-lg">
                            <Loader2 size={16} className={cn(isSyncing && "animate-spin")} />
                          </div>
                          {isSyncing ? '正在同步库...' : '同步全量文档'}
                        </button>

                        <button
                          type="button"
                          onClick={() => uploadInputRef.current?.click()}
                          disabled={isUploading}
                          className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-bold text-slate-700 hover:bg-slate-50 transition-colors disabled:opacity-50"
                        >
                          <div className="p-1.5 bg-emerald-50 text-emerald-600 rounded-lg">
                            {isUploading ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
                          </div>
                          {isUploading ? '正在上传...' : '批量导入校史记忆'}
                        </button>
                        <input ref={uploadInputRef} type="file" multiple className="hidden" onChange={onUpload} disabled={isUploading} accept=".pdf,.ppt,.pptx,.docx,.txt,.doc,.jpg,.jpeg,.png,.webp,.mp4,.mp3,.wav,.avi,.mov" />

                        <button
                          onClick={onAddLink}
                          disabled={isUploading}
                          className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-bold text-slate-700 hover:bg-slate-50 transition-colors disabled:opacity-50"
                        >
                          <div className="p-1.5 bg-purple-50 text-purple-600 rounded-lg">
                            <LinkIcon size={16} />
                          </div>
                          收录外部链接
                        </button>
                      </div>
                    )}
                  </div>
                )}

                <div className="hidden sm:flex flex-col items-end">
                  <span className="text-base font-black text-slate-800 leading-none">{user.username}</span>
                  <span className={cn(
                    "text-base font-black uppercase tracking-wider mt-1 px-3 py-1 rounded",
                    user.role === 'admin' ? "bg-rose-100 text-rose-600" : 
                    user.role === 'teacher' ? "bg-blue-100 text-blue-600" : "bg-emerald-100 text-emerald-600"
                  )}>
                    {user.role === 'admin' ? '管理员' : user.role === 'teacher' ? '教师' : '学生'}
                  </span>
                </div>
                <button
                  onClick={onLogout}
                  className="p-2 text-slate-400 hover:text-rose-600 hover:bg-rose-50 rounded-xl transition-all duration-300 group"
                  title="退出登录"
                >
                  <LogOut size={20} className="group-hover:-translate-x-1 transition-transform" />
                </button>
              </div>
            )}
            {!user && (
              <button
                onClick={onLogin}
                className="h-11 px-5 rounded-xl bg-slate-900 text-white text-sm font-black hover:bg-slate-800 transition-all"
              >
                登录 / 注册
              </button>
            )}

            {/* Mobile menu button */}
            <div className="lg:hidden flex items-center">
              <button
                onClick={() => setIsOpen(!isOpen)}
                className="p-2 rounded-xl text-slate-600 hover:bg-slate-100 transition-colors"
              >
                {isOpen ? <X size={24} /> : <Menu size={24} />}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Mobile Navigation */}
      {isOpen && (
        <div className="lg:hidden bg-white border-t border-slate-100 animate-in slide-in-from-top duration-300">
          <div className="px-4 pt-4 pb-6 space-y-2">
            {navItems.map((item) => (
              <div key={item.label} className="space-y-1">
                {item.path ? (
                  <Link
                    to={item.path}
                    onClick={() => setIsOpen(false)}
                    className={cn(
                      "flex items-center gap-3 px-4 py-3 rounded-2xl text-base font-bold transition-all",
                      location.pathname === item.path 
                        ? "bg-blue-600 text-white" 
                        : "text-slate-600 hover:bg-slate-50"
                    )}
                  >
                    {item.icon}
                    {item.label}
                  </Link>
                ) : (
                  <>
                    <button
                      onClick={() => toggleSubmenu(item.label)}
                      className={cn(
                        "w-full flex items-center justify-between px-4 py-3 rounded-2xl text-base font-bold text-slate-600 hover:bg-slate-50 transition-all",
                        activeSubmenu === item.label && "bg-slate-50 text-blue-600"
                      )}
                    >
                      <div className="flex items-center gap-3">
                        {item.icon}
                        {item.label}
                      </div>
                      <ChevronDown size={18} className={cn("transition-transform", activeSubmenu === item.label && "rotate-180")} />
                    </button>
                    {activeSubmenu === item.label && item.children && (
                      <div className="pl-12 space-y-1 py-1">
                        {item.children.map((child) => (
                          <Link
                            key={child.path}
                            to={child.path}
                            onClick={() => setIsOpen(false)}
                            className={cn(
                              "flex items-center gap-3 px-4 py-2.5 rounded-xl text-sm font-bold transition-all",
                              location.pathname === child.path
                                ? "text-blue-600 bg-blue-50"
                                : "text-slate-500 hover:text-slate-900"
                            )}
                          >
                            {child.label}
                          </Link>
                        ))}
                      </div>
                    )}
                  </>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </nav>
  );
}
