import { useState, useEffect, useMemo, useCallback } from 'react';
import { 
  FolderLock, 
  ArrowLeft, 
  Upload, 
  FileText, 
  Trash2, 
  Loader2, 
  Search,
  Plus,
  Lock,
  Globe,
  Clock,
  HardDrive,
  Sparkles,
  Rocket,
  Lightbulb
} from 'lucide-react';
import axios from 'axios';

interface PrivateFile {
  fileName: string;
  objectName: string;
  uploadTime?: string;
  size?: string;
}

interface PrivateSpaceProps {
  user: { userId: string; username: string; role: string };
  onBack: () => void;
}

export function PrivateSpace({ user, onBack }: PrivateSpaceProps) {
  const [files, setFiles] = useState<PrivateFile[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const authHeaders = useMemo(() => ({
    'X-User-Id': user.userId,
    'X-User-Role': user.role
  }), [user.userId, user.role]);

  // 模拟获取私有文件列表 (实际应调用后端接口获取该 userId 的 assets)
  // 为了演示，我们先使用 mock 数据，待后端列表接口完善
  const fetchPrivateFiles = useCallback(async () => {
    setIsLoading(true);
    try {
      const response = await axios.get('/api/asset/list', {
        headers: authHeaders,
        params: {
          userId: user.userId,
          role: 'private'
        }
      });
      setFiles(response.data);
    } catch (err) {
      console.error('Fetch error:', err);
      // alert('获取文件列表失败');
    } finally {
      setIsLoading(false);
    }
  }, [authHeaders, user.userId]);

  useEffect(() => {
    fetchPrivateFiles();
  }, [fetchPrivateFiles]);

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setIsUploading(true);
    const formData = new FormData();
    formData.append('file', file);
    formData.append('role', 'private'); // 标记为私有
    try {
      await axios.post('/api/asset/upload', formData, {
        headers: authHeaders
      });
      // 上传成功后刷新列表
      fetchPrivateFiles();
    } catch (err) {
      console.error('Upload error:', err);
      alert('上传失败，请稍后再试');
    } finally {
      setIsUploading(false);
    }
  };

  const handleDelete = async (objectName: string) => {
    if (!confirm('确定要彻底删除该私有资料吗？')) return;
    
    try {
      await axios.delete('/api/asset/delete', {
        headers: authHeaders,
        params: { objectName }
      });
      fetchPrivateFiles();
    } catch (err) {
      console.error('Delete error:', err);
      alert('删除失败');
    }
  };

  const filteredFiles = files.filter(f => 
    f.fileName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col font-sans">
      {/* Header */}
      <header className="h-28 bg-white border-b border-slate-200 flex items-center justify-between px-12 shrink-0 shadow-sm sticky top-0 z-10">
        <div className="flex items-center gap-6">
          <button 
            onClick={onBack}
            className="w-12 h-12 bg-slate-100 hover:bg-slate-200 rounded-2xl flex items-center justify-center text-slate-600 transition-all active:scale-95"
          >
            <ArrowLeft size={24} />
          </button>
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 bg-amber-500 rounded-2xl flex items-center justify-center text-white shadow-lg shadow-amber-100">
              <FolderLock size={28} />
            </div>
            <div>
              <h1 className="text-3xl font-black text-slate-900 tracking-tight">
                个人私有空间 <span className="text-amber-500 ml-2">Private</span>
              </h1>
              <p className="text-base font-bold text-slate-400 uppercase tracking-widest">
                专属数字化记忆库 · 仅本人可见
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="text-right mr-4 hidden md:block">
            <p className="text-sm font-black text-slate-300 uppercase tracking-widest">当前用户</p>
            <p className="text-lg font-bold text-slate-900">{user.username}</p>
          </div>
          <label className="h-16 px-10 bg-slate-900 text-white rounded-2xl text-lg font-black flex items-center gap-3 cursor-pointer hover:bg-slate-800 transition-all shadow-lg shadow-slate-200 active:scale-95">
            {isUploading ? <Loader2 size={20} className="animate-spin" /> : <Plus size={20} />}
            <span>{isUploading ? '正在同步...' : '导入私有资料'}</span>
            <input type="file" className="hidden" onChange={handleFileUpload} disabled={isUploading} accept=".pdf,.ppt,.pptx,.docx,.txt,.doc,.jpg,.jpeg,.png,.webp,.mp4,.mp3,.wav,.avi,.mov" />
          </label>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 px-12 py-10 max-w-[1700px] mx-auto w-full">
        <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_360px] gap-8 items-start">
          <div>
            <div className="flex flex-col md:flex-row gap-7 mb-12">
              <div className="flex-1 bg-white p-7 rounded-3xl border border-slate-100 shadow-sm flex items-center gap-6">
                <div className="w-14 h-14 bg-blue-50 text-blue-600 rounded-xl flex items-center justify-center">
                  <HardDrive size={26} />
                </div>
                <div>
                  <p className="text-sm font-black text-slate-400 uppercase">存储状态</p>
                  <p className="text-2xl font-black text-slate-900">{files.length} <span className="text-slate-400 text-base">个文档</span></p>
                </div>
              </div>
              
              <div className="flex-[2] relative">
                <div className="absolute left-6 top-1/2 -translate-y-1/2 text-slate-400">
                  <Search size={20} />
                </div>
                <input 
                  type="text"
                  placeholder="搜索我的私有资料..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full h-full min-h-[5rem] pl-16 pr-6 bg-white border border-slate-100 rounded-3xl shadow-sm focus:outline-none focus:border-slate-900 focus:ring-4 focus:ring-slate-50 text-xl font-bold transition-all"
                />
              </div>
            </div>

            <div className="space-y-4">
              <h2 className="text-base font-black text-slate-400 uppercase tracking-widest ml-2 mb-5 flex items-center gap-2">
                <FileText size={18} /> 我的文档列表
              </h2>
              
              {isLoading ? (
                <div className="flex flex-col items-center justify-center py-32 text-slate-300">
                  <Loader2 size={48} className="animate-spin mb-4" />
                  <p className="font-bold">正在加载私人记忆...</p>
                </div>
              ) : filteredFiles.length > 0 ? (
                <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-5">
                  {filteredFiles.map((file, idx) => (
                    <div 
                      key={idx}
                      className="bg-white p-6 rounded-[2rem] border border-slate-100 hover:border-amber-200 hover:shadow-xl hover:shadow-amber-50/50 transition-all group relative overflow-hidden"
                    >
                      <div className="flex items-start gap-5">
                        <div className="w-14 h-14 bg-slate-50 text-slate-400 group-hover:bg-amber-50 group-hover:text-amber-500 rounded-2xl flex items-center justify-center transition-colors">
                          <FileText size={28} />
                        </div>
                        <div className="flex-1 min-w-0">
                          <a 
                            href={`/api/asset/view?objectName=${encodeURIComponent(file.objectName)}&userId=${encodeURIComponent(user.username)}&role=${encodeURIComponent(user.role)}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-lg font-black text-slate-900 truncate block hover:text-amber-500 transition-colors mb-1"
                          >
                            {file.fileName}
                          </a>
                          <div className="flex items-center gap-4 text-xs font-bold text-slate-400">
                            <span className="flex items-center gap-1"><Clock size={12} /> {file.uploadTime}</span>
                            <span>{file.size}</span>
                            <span className="px-2 py-0.5 bg-amber-50 text-amber-600 rounded-md text-[10px] uppercase font-black tracking-wider">Private</span>
                          </div>
                        </div>
                      </div>
                      
                      <div className="absolute top-6 right-6 flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
                        <button 
                          onClick={() => handleDelete(file.objectName)}
                          className="w-10 h-10 bg-rose-50 text-rose-500 rounded-xl flex items-center justify-center hover:bg-rose-100 transition-colors"
                        >
                          <Trash2 size={18} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="bg-white rounded-[3rem] border-2 border-dashed border-slate-100 py-32 flex flex-col items-center justify-center text-center px-10">
                  <div className="w-24 h-24 bg-slate-50 rounded-full flex items-center justify-center text-slate-200 mb-8">
                    <FolderLock size={48} />
                  </div>
                  <h3 className="text-2xl font-black text-slate-900 mb-3">这里还是一片空白</h3>
                  <p className="text-slate-400 font-bold max-w-md mx-auto mb-10">
                    上传您的个人笔记、研究资料或感悟，AI 将仅为您个人提供基于这些资料的专属回答。
                  </p>
                  <label className="px-10 h-16 bg-slate-100 hover:bg-slate-200 text-slate-600 rounded-2xl font-black flex items-center gap-3 cursor-pointer transition-all active:scale-95">
                    <Upload size={20} />
                    <span>立即开启个人记忆</span>
                    <input type="file" className="hidden" onChange={handleFileUpload} disabled={isUploading} accept=".pdf,.ppt,.pptx,.docx,.txt,.doc,.jpg,.jpeg,.png,.webp,.mp4,.mp3,.wav,.avi,.mov" />
                  </label>
                </div>
              )}
            </div>
          </div>

          <aside className="space-y-5 xl:sticky xl:top-32">
            <div className="rounded-3xl bg-gradient-to-br from-indigo-600 via-blue-600 to-cyan-500 text-white p-6 shadow-xl">
              <div className="flex items-center justify-between">
                <p className="text-sm font-black uppercase tracking-widest text-blue-100">Memory Studio</p>
                <Sparkles size={20} />
              </div>
              <p className="mt-3 text-2xl font-black leading-tight">私人记忆创作区</p>
              <p className="mt-2 text-sm font-semibold text-blue-100 leading-relaxed">持续沉淀你的资料，形成专属知识画像与问答能力。</p>
            </div>

            <div className="bg-white rounded-3xl border border-slate-100 p-5 shadow-sm space-y-3">
              <p className="text-sm font-black text-slate-400 uppercase tracking-widest">快捷筛选</p>
              <button onClick={() => setSearchQuery('报告')} className="w-full h-12 rounded-2xl bg-slate-50 hover:bg-slate-100 text-slate-700 text-base font-bold flex items-center gap-3 px-4 transition-colors">
                <Lightbulb size={18} className="text-amber-500" />
                学习报告
              </button>
              <button onClick={() => setSearchQuery('项目')} className="w-full h-12 rounded-2xl bg-slate-50 hover:bg-slate-100 text-slate-700 text-base font-bold flex items-center gap-3 px-4 transition-colors">
                <Rocket size={18} className="text-blue-500" />
                项目资料
              </button>
              <button onClick={() => setSearchQuery('')} className="w-full h-12 rounded-2xl bg-slate-900 hover:bg-slate-800 text-white text-base font-bold flex items-center justify-center transition-colors">
                显示全部文档
              </button>
            </div>

            <div className="bg-white rounded-3xl border border-slate-100 p-5 shadow-sm space-y-3">
              <p className="text-sm font-black text-slate-400 uppercase tracking-widest">今日灵感</p>
              <div className="rounded-2xl bg-amber-50 text-amber-700 px-4 py-3 text-sm font-bold">把课堂笔记与项目复盘放在同一主题下，更容易被 AI 串联回答。</div>
              <div className="rounded-2xl bg-blue-50 text-blue-700 px-4 py-3 text-sm font-bold">上传文件后用统一命名规则，检索速度和准确度会更高。</div>
            </div>
          </aside>
        </div>
      </main>

      {/* Footer Info */}
      <footer className="p-10 border-t border-slate-100 flex flex-col md:flex-row items-center justify-between gap-6">
        <div className="flex items-center gap-4 text-slate-300">
          <Lock size={16} />
          <p className="text-[10px] font-black uppercase tracking-[0.3em]">
            End-to-End Encryption · Identity Isolated Storage
          </p>
        </div>
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-2 text-slate-400">
            <Globe size={16} />
            <span className="text-xs font-bold">同步状态：已加密</span>
          </div>
          <div className="w-2 h-2 bg-emerald-500 rounded-full animate-pulse" />
        </div>
      </footer>
    </div>
  );
}
