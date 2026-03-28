import { useCallback, useEffect, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import axios from 'axios';
import { 
  Sparkles, X, Loader2, Calendar, 
  Award, Trophy, Upload, FileText, ExternalLink, Image, Video, Music, Trash2
} from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface HonorNode {
  name: string;
  type?: 'year' | 'category' | 'item';
  children?: HonorNode[];
  text?: string;
  level?: string;
  year?: string;
  timestamp?: string;
  category?: string;
  fileName?: string;
  objectName?: string;
}

interface TreeNodeClickParams {
  data?: HonorNode;
}

interface TreeRenderParams {
  data?: HonorNode;
}

type LocalUserRole = 'student' | 'teacher' | 'admin';

interface LocalUser {
  userId: string;
  role: LocalUserRole;
}

export function CampusHonorTree({ currentUser }: { currentUser: LocalUser | null }) {
  const [treeData, setTreeData] = useState<HonorNode | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedHonor, setSelectedHonor] = useState<HonorNode | null>(null);
  const [bubbleContent, setBubbleContent] = useState<string>('');
  const [loadingBubble, setLoadingBubble] = useState(false);
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [honorLevel, setHonorLevel] = useState('校级');
  const [honorCategory, setHonorCategory] = useState('学术');
  const [honorTimestamp, setHonorTimestamp] = useState('');
  const [honorDescription, setHonorDescription] = useState('');
  const [isUploadingHonor, setIsUploadingHonor] = useState(false);
  const [isDeletingHonor, setIsDeletingHonor] = useState(false);
  const [uploadStatus, setUploadStatus] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const isAdmin = currentUser?.role === 'admin';

  const getFileExtension = (value?: string) => {
    if (!value) return '';
    const clean = value.split('?')[0];
    const index = clean.lastIndexOf('.');
    if (index < 0) return '';
    return clean.substring(index + 1).toLowerCase();
  };

  const resolveHonorPreviewKind = (honor: HonorNode) => {
    const ext = getFileExtension(honor.fileName || honor.objectName);
    if (['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'svg'].includes(ext)) return 'image';
    if (['mp4', 'webm', 'ogg', 'mov', 'm4v'].includes(ext)) return 'video';
    if (['mp3', 'wav', 'ogg', 'm4a', 'aac', 'flac'].includes(ext)) return 'audio';
    if (ext === 'pdf') return 'pdf';
    return 'other';
  };

  const getHonorPreviewUrl = (honor: HonorNode) => {
    if (!honor.objectName) return '';
    const params = new URLSearchParams();
    params.set('objectName', honor.objectName);
    params.set('role', currentUser?.role || 'guest');
    if (currentUser?.userId) {
      params.set('userId', currentUser.userId);
    }
    return `/api/asset/view?${params.toString()}`;
  };

  const formatHonorMonthLabel = (timestamp?: string, year?: string) => {
    const value = `${timestamp || ''}`.trim();
    const monthMatch = value.match(/^(\d{4}-\d{2})/);
    if (monthMatch) return monthMatch[1];
    const dateMatch = value.match(/^(\d{4})-(\d{2})-\d{2}/);
    if (dateMatch) return `${dateMatch[1]}-${dateMatch[2]}`;
    return year || '';
  };

  const deriveFileBaseName = useCallback((value?: string) => {
    if (!value) return '';
    const normalized = value.replace(/\\/g, '/').split('?')[0];
    const fileWithExt = normalized.substring(normalized.lastIndexOf('/') + 1);
    const decoded = (() => {
      try {
        return decodeURIComponent(fileWithExt);
      } catch {
        return fileWithExt;
      }
    })();
    return decoded.replace(/\.[^.]+$/, '').trim();
  }, []);

  const isLikelyGarbledHonorText = useCallback((value: string) => {
    if (!value) return true;
    const compact = value.replace(/\s+/g, '');
    if (compact.length < 4) return true;
    if (value.includes('�')) return true;
    if (/[\u2500-\u257f]/.test(value)) return true;

    const cjkCount = (compact.match(/[\u4e00-\u9fff]/g) || []).length;
    const letterCount = (compact.match(/[A-Za-z]/g) || []).length;
    const digitCount = (compact.match(/[0-9]/g) || []).length;
    const weirdCount = (compact.match(/[^\u4e00-\u9fffA-Za-z0-9，。！？；：、“”‘’（）()《》【】[\]—\-·.,!?;:'"@#%&*+=_]/g) || []).length;
    const tokens = value.replace(/[^\u4e00-\u9fffA-Za-z0-9]+/g, ' ').split(/\s+/).filter(Boolean);
    const singleLatinTokenCount = tokens.filter(token => /^[A-Za-z]$/.test(token)).length;
    const latinTokenCount = tokens.filter(token => /^[A-Za-z]+$/.test(token)).length;
    const shortLatinTokenCount = tokens.filter(token => /^[A-Za-z]{1,3}$/.test(token)).length;

    const cjkRatio = cjkCount / Math.max(compact.length, 1);
    const weirdRatio = weirdCount / Math.max(compact.length, 1);
    const isolatedLatinRatio = singleLatinTokenCount / Math.max(tokens.length, 1);
    const shortLatinTokenRatio = shortLatinTokenCount / Math.max(latinTokenCount, 1);
    const latinHeavyNoise = letterCount > 12 && cjkCount <= 4 && isolatedLatinRatio > 0.35;
    const lowInfoNoise = cjkCount + letterCount + digitCount < 5;
    const fragmentedLatinNoise = cjkCount <= 6 && latinTokenCount >= 3 && shortLatinTokenRatio > 0.65;
    const mixedCorruptionNoise = cjkCount > 0 && cjkCount <= 12 && latinTokenCount >= 4 && shortLatinTokenRatio > 0.55;

    return weirdRatio > 0.15 || (cjkRatio < 0.08 && latinHeavyNoise) || lowInfoNoise || fragmentedLatinNoise || mixedCorruptionNoise;
  }, []);

  const getSafeHonorTitle = useCallback((honor: HonorNode) => {
    const raw = `${honor.text || honor.name || ''}`.trim();
    const removeControlChars = (input: string) =>
      input
        .split('')
        .map((char) => {
          const code = char.charCodeAt(0);
          return (code < 32 || code === 127) ? ' ' : char;
        })
        .join('');
    const normalized = removeControlChars(raw)
      .replace(/\s+/g, ' ')
      .replace(/�+/g, '')
      .trim();

    if (normalized && !isLikelyGarbledHonorText(normalized)) {
      return normalized.length > 180 ? normalized.slice(0, 180) : normalized;
    }

    const fromFileName = deriveFileBaseName(honor.fileName || honor.objectName);
    if (fromFileName && !isLikelyGarbledHonorText(fromFileName)) return fromFileName;

    const dateText = honor.timestamp?.split('T')[0] || honor.year || '';
    const categoryText = honor.category || '校园荣誉';
    return `${dateText ? `${dateText} ` : ''}${categoryText}`.trim();
  }, [deriveFileBaseName, isLikelyGarbledHonorText]);

  const normalizeHonorNode = useCallback((honor: HonorNode): HonorNode => {
    if (honor.children && honor.children.length) {
      return {
        ...honor,
        children: honor.children.map((child) => normalizeHonorNode(child))
      };
    }
    const safeText = getSafeHonorTitle(honor);
    const safeName = safeText.length > 26 ? `${safeText.slice(0, 26)}...` : safeText;
    return {
      ...honor,
      text: safeText,
      name: safeName
    };
  }, [getSafeHonorTitle]);

  const fetchTreeData = useCallback(async () => {
    try {
      const res = await axios.get<HonorNode[]>('/api/memory/honor-tree');
      const root: HonorNode = {
        name: "校园荣誉生长树",
        children: (res.data || []).map((node) => normalizeHonorNode(node))
      };
      setTreeData(root);
    } catch (err) {
      console.error("Failed to fetch honor tree:", err);
    } finally {
      setLoading(false);
    }
  }, [normalizeHonorNode]);

  useEffect(() => {
    fetchTreeData();
  }, [fetchTreeData]);

  const onNodeClick = async (params: TreeNodeClickParams) => {
    if (params.data && !params.data.children) {
      const honor = normalizeHonorNode(params.data);
      setSelectedHonor(honor);
      setBubbleContent('');
      setLoadingBubble(true);
      
      try {
        const res = await axios.post('/api/memory/honor-narrative', {
          text: honor.text || honor.name || '',
          level: honor.level || '',
          category: honor.category || honor.name || ''
        });
        setBubbleContent(res.data.answer || '暂无详细记忆叙事');
      } catch {
        setBubbleContent('加载记忆叙事失败');
      } finally {
        setLoadingBubble(false);
      }
    }
  };

  const submitHonorUpload = async () => {
    if (!isAdmin) {
      setUploadStatus({ type: 'error', message: '仅管理员可上传校园荣誉' });
      return;
    }
    if (!uploadFile) {
      setUploadStatus({ type: 'error', message: '请先选择要上传的荣誉文件' });
      return;
    }
    if (!honorLevel || !honorCategory) {
      setUploadStatus({ type: 'error', message: '请完善荣誉级别与荣誉分类' });
      return;
    }
    setIsUploadingHonor(true);
    setUploadStatus(null);
    try {
      const formData = new FormData();
      formData.append('file', uploadFile);
      formData.append('honorLevel', honorLevel);
      formData.append('honorCategory', honorCategory);
      if (honorDescription.trim()) {
        formData.append('description', honorDescription.trim());
      }
      if (honorTimestamp) {
        const normalizedTimestamp = /^\d{4}-\d{2}$/.test(honorTimestamp)
          ? `${honorTimestamp}-01T00:00:00`
          : honorTimestamp.length === 16
            ? `${honorTimestamp}:00`
            : honorTimestamp;
        formData.append('timestamp', normalizedTimestamp);
      }
      formData.append('role', 'all');

      const res = await axios.post('/api/asset/upload-honor', formData, {
        headers: {
          'X-User-Id': currentUser?.userId || '',
          'X-User-Role': currentUser?.role || ''
        }
      });
      setUploadStatus({ type: 'success', message: res.data?.message || '荣誉上传成功' });
      setShowUploadModal(false);
      setUploadFile(null);
      setHonorDescription('');
      setHonorTimestamp('');
      setLoading(true);
      await fetchTreeData();
    } catch (error) {
      const axiosError = error as { response?: { data?: { message?: string } }; message?: string };
      setUploadStatus({
        type: 'error',
        message: axiosError.response?.data?.message || axiosError.message || '荣誉上传失败'
      });
    } finally {
      setIsUploadingHonor(false);
    }
  };

  const deleteHonorFile = async () => {
    if (!isAdmin || !selectedHonor?.objectName) return;
    setIsDeletingHonor(true);
    try {
      await axios.delete('/api/asset/delete', {
        params: {
          objectName: selectedHonor.objectName
        },
        headers: {
          'X-User-Id': currentUser?.userId || '',
          'X-User-Role': currentUser?.role || ''
        }
      });
      setUploadStatus({ type: 'success', message: '荣誉信息已删除（含文件与记录）' });
      setSelectedHonor(null);
      setLoading(true);
      await fetchTreeData();
    } catch (error) {
      const axiosError = error as { response?: { data?: string }; message?: string };
      setUploadStatus({
        type: 'error',
        message: axiosError.response?.data || axiosError.message || '荣誉信息删除失败'
      });
    } finally {
      setIsDeletingHonor(false);
    }
  };

  const getOption = () => {
    if (!treeData) return {};
    return {
      tooltip: {
        trigger: 'item',
        triggerOn: 'mousemove'
      },
      series: [
        {
          type: 'tree',
          data: [treeData],
          top: '5%',
          left: '10%',
          bottom: '5%',
          right: '32%',
          symbolSize: (_value: unknown, params: TreeRenderParams) => {
            const nodeType = params.data?.type;
            if (nodeType === 'year') return 18;
            if (nodeType === 'category') return 14;
            return 10;
          },
          label: {
            position: 'left',
            verticalAlign: 'middle',
            align: 'right',
            fontSize: 14,
            lineHeight: 20,
            fontWeight: 'bold',
            color: '#475569'
          },
          leaves: {
            label: {
              position: 'right',
              verticalAlign: 'middle',
              align: 'left',
              color: '#334155',
              fontSize: 14,
              lineHeight: 20,
              width: 220,
              overflow: 'truncate'
            }
          },
          emphasis: {
            focus: 'descendant'
          },
          expandAndCollapse: true,
          animationDuration: 550,
          animationDurationUpdate: 750,
          itemStyle: {
            color: (params: TreeRenderParams) => {
                const nodeType = params.data?.type;
                if (nodeType === 'year') return '#3b82f6';
                if (nodeType === 'category') return '#10b981';
                return '#f59e0b';
            },
            borderColor: '#fff',
            borderWidth: 2
          },
          lineStyle: {
            color: '#cbd5e1',
            curveness: 0.5
          }
        }
      ]
    };
  };

  return (
    <div className="p-6 bg-slate-50 min-h-screen">
      <div className="max-w-7xl mx-auto">
        <div className="flex items-center justify-between mb-8">
          <div>
            <h1 className="text-3xl font-black text-slate-900 flex items-center gap-3">
              <Award className="text-blue-600" size={36} />
              校园荣誉生长树
            </h1>
            <p className="text-slate-500 mt-2 font-medium">见证校园文化的每一个闪光时刻 (RAG 增强版)</p>
          </div>
          <div className="flex items-center gap-3">
            {isAdmin && (
              <button
                onClick={() => setShowUploadModal(true)}
                className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-blue-600 text-white font-black text-sm hover:bg-blue-700 transition-all shadow-lg shadow-blue-100"
              >
                <Upload size={16} />
                上传荣誉
              </button>
            )}
            {uploadStatus && (
              <div className={`px-3 py-1.5 rounded-xl text-xs font-bold ${uploadStatus.type === 'success' ? 'bg-emerald-50 text-emerald-700 border border-emerald-100' : 'bg-rose-50 text-rose-700 border border-rose-100'}`}>
                {uploadStatus.message}
              </div>
            )}
            <div className="flex gap-2">
            <div className="flex items-center gap-2 px-3 py-1.5 bg-white border border-slate-200 rounded-full text-xs font-bold text-slate-500">
                <div className="w-2 h-2 rounded-full bg-blue-500" /> 年份
            </div>
            <div className="flex items-center gap-2 px-3 py-1.5 bg-white border border-slate-200 rounded-full text-xs font-bold text-slate-500">
                <div className="w-2 h-2 rounded-full bg-emerald-500" /> 分类
            </div>
            <div className="flex items-center gap-2 px-3 py-1.5 bg-white border border-slate-200 rounded-full text-xs font-bold text-slate-500">
                <div className="w-2 h-2 rounded-full bg-amber-500" /> 荣誉项
            </div>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-[2.5rem] border border-slate-200 shadow-2xl shadow-blue-100/50 p-8 h-[750px] relative overflow-hidden group">
          <div className="absolute inset-0 bg-gradient-to-tr from-blue-50/10 via-transparent to-emerald-50/10 pointer-events-none" />
          {loading ? (
            <div className="flex flex-col items-center justify-center h-full gap-4">
              <Loader2 className="animate-spin text-blue-600" size={48} />
              <p className="text-slate-400 font-bold animate-pulse">正在生成数字化生长树...</p>
            </div>
          ) : (
            <ReactECharts 
              option={getOption()} 
              style={{ height: '100%', width: '100%' }}
              onEvents={{ 'click': onNodeClick }}
            />
          )}
        </div>

        {/* 记忆气泡 Modal */}
        {selectedHonor && (
          <div className="fixed inset-0 z-[200] flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-sm animate-in fade-in duration-300">
            <div className="bg-white w-full max-w-7xl rounded-[2.5rem] shadow-2xl overflow-hidden grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_420px] max-h-[92vh] animate-in zoom-in-95 duration-300 border border-slate-100">
              <div className="flex flex-col min-h-0">
                <div className="p-8 bg-gradient-to-br from-blue-600 to-indigo-700 text-white relative">
                  <button 
                    onClick={() => setSelectedHonor(null)}
                    className="absolute top-6 right-6 p-2 hover:bg-white/20 rounded-full transition-colors"
                  >
                    <X size={20} />
                  </button>
                  <div className="flex items-start gap-5">
                    <div className="p-4 bg-white/20 backdrop-blur-md rounded-3xl border border-white/20">
                      <Award size={32} />
                    </div>
                    <div className="flex-1">
                      <h3 className="text-2xl font-black leading-tight tracking-tight pr-8">{selectedHonor.text || selectedHonor.name || '校园荣誉'}</h3>
                      <div className="flex flex-wrap items-center gap-3 mt-3">
                        <span className="flex items-center gap-1.5 bg-white/10 px-3 py-1 rounded-xl text-xs font-bold backdrop-blur-sm border border-white/10">
                          <Calendar size={14} /> {formatHonorMonthLabel(selectedHonor.timestamp, selectedHonor.year)}
                        </span>
                        <span className="flex items-center gap-1.5 bg-white/10 px-3 py-1 rounded-xl text-xs font-bold backdrop-blur-sm border border-white/10">
                          <Trophy size={14} /> {selectedHonor.level}
                        </span>
                        <span className="flex items-center gap-1.5 bg-emerald-400/20 px-3 py-1 rounded-xl text-xs font-bold backdrop-blur-sm border border-emerald-400/20 text-emerald-100">
                          {selectedHonor.category || selectedHonor.name}
                        </span>
                        {isAdmin && selectedHonor.objectName && (
                          <button
                            onClick={deleteHonorFile}
                            disabled={isDeletingHonor}
                            className="flex items-center gap-1.5 bg-rose-500/20 px-3 py-1 rounded-xl text-xs font-bold border border-rose-300/30 text-rose-100 hover:bg-rose-500/30 disabled:bg-white/10 disabled:text-white/50 disabled:border-white/20 disabled:cursor-not-allowed"
                          >
                            {isDeletingHonor ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />}
                            删除整条信息
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                </div>

                <div className="flex-1 overflow-y-auto p-8 space-y-10 custom-scrollbar min-h-0">
                  <section>
                    <div className="flex items-center gap-2 text-blue-600 font-black uppercase tracking-widest text-xs mb-5">
                      <Sparkles size={16} />
                      <span>AI 记忆叙事 (Memory Narrative)</span>
                    </div>
                    <div className="bg-slate-50 border border-slate-100 rounded-[2rem] p-8 relative overflow-hidden">
                      <div className="absolute top-0 right-0 p-4 opacity-10">
                          <History size={120} />
                      </div>
                      {loadingBubble ? (
                        <div className="flex flex-col items-center justify-center py-12 gap-4">
                          <Loader2 className="animate-spin text-blue-500" size={36} />
                          <div className="text-center">
                              <p className="text-blue-500 font-black text-sm uppercase tracking-wider">正在合成叙事...</p>
                              <p className="text-slate-400 text-xs mt-1">检索校史档案 & 整合 RAG 背景</p>
                          </div>
                        </div>
                      ) : (
                        <div className="prose prose-slate prose-blue max-w-none relative z-10">
                          <ReactMarkdown remarkPlugins={[remarkGfm]}>
                            {bubbleContent}
                          </ReactMarkdown>
                        </div>
                      )}
                    </div>
                  </section>

                </div>
              </div>

              <aside className="border-l border-slate-100 bg-slate-50/80 p-5 xl:p-6 flex flex-col gap-4 min-h-0">
                <div className="flex items-center justify-between">
                  <h4 className="text-sm font-black uppercase tracking-widest text-slate-500">文件预览</h4>
                  <div className="flex items-center gap-2">
                    {selectedHonor.objectName && (
                      <a
                        href={getHonorPreviewUrl(selectedHonor)}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-1.5 text-xs font-black text-blue-600 hover:text-blue-700"
                      >
                        <ExternalLink size={14} />
                        新窗口
                      </a>
                    )}
                    {isAdmin && selectedHonor.objectName && (
                      <button
                        onClick={deleteHonorFile}
                        disabled={isDeletingHonor}
                        className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-rose-50 text-rose-600 text-xs font-black hover:bg-rose-100 disabled:bg-slate-200 disabled:text-slate-400 disabled:cursor-not-allowed"
                      >
                        {isDeletingHonor ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />}
                        删除整条
                      </button>
                    )}
                  </div>
                </div>

                <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
                  <p className="text-xs font-black text-slate-400 uppercase tracking-widest">文件名</p>
                  <p className="mt-1 text-sm font-bold text-slate-700 break-all">
                    {selectedHonor.fileName || selectedHonor.objectName || '暂无文件信息'}
                  </p>
                </div>

                <div className="flex-1 min-h-[320px] rounded-3xl border border-slate-200 bg-white overflow-hidden">
                  {!selectedHonor.objectName ? (
                    <div className="h-full flex flex-col items-center justify-center text-slate-400 gap-3 px-8 text-center">
                      <FileText size={32} />
                      <p className="text-sm font-bold">该荣誉记录暂无可预览文件</p>
                    </div>
                  ) : resolveHonorPreviewKind(selectedHonor) === 'image' ? (
                    <div className="h-full overflow-auto bg-slate-100 p-3">
                      <img
                        src={getHonorPreviewUrl(selectedHonor)}
                        alt={selectedHonor.fileName || '荣誉文件'}
                        className="w-full h-auto rounded-2xl border border-slate-200 bg-white"
                      />
                    </div>
                  ) : resolveHonorPreviewKind(selectedHonor) === 'pdf' ? (
                    <iframe
                      src={getHonorPreviewUrl(selectedHonor)}
                      className="w-full h-full border-none"
                      title="荣誉 PDF 预览"
                    />
                  ) : resolveHonorPreviewKind(selectedHonor) === 'video' ? (
                    <div className="h-full flex items-center justify-center bg-black p-2">
                      <video controls className="max-h-full max-w-full rounded-xl" src={getHonorPreviewUrl(selectedHonor)} />
                    </div>
                  ) : resolveHonorPreviewKind(selectedHonor) === 'audio' ? (
                    <div className="h-full flex flex-col items-center justify-center gap-4 p-6 text-center">
                      <Music size={32} className="text-purple-500" />
                      <audio controls className="w-full" src={getHonorPreviewUrl(selectedHonor)} />
                    </div>
                  ) : (
                    <div className="h-full flex flex-col items-center justify-center gap-4 px-8 text-center">
                      <div className="flex items-center justify-center w-14 h-14 rounded-2xl bg-blue-50 text-blue-600">
                        {resolveHonorPreviewKind(selectedHonor) === 'video' ? <Video size={24} /> : <Image size={24} />}
                      </div>
                      <p className="text-sm font-bold text-slate-600">当前文件暂不支持内嵌预览</p>
                      <a
                        href={getHonorPreviewUrl(selectedHonor)}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl bg-blue-600 text-white text-sm font-black hover:bg-blue-700"
                      >
                        <ExternalLink size={14} />
                        打开文件
                      </a>
                    </div>
                  )}
                </div>
              </aside>
            </div>
          </div>
        )}

        {showUploadModal && (
          <div className="fixed inset-0 z-[210] flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-sm">
            <div className="w-full max-w-xl bg-white rounded-[2rem] border border-slate-100 shadow-2xl p-7 space-y-5">
              <div className="flex items-center justify-between">
                <h3 className="text-xl font-black text-slate-900">上传校园荣誉</h3>
                <button
                  onClick={() => setShowUploadModal(false)}
                  className="p-2 rounded-full hover:bg-slate-100 text-slate-500"
                >
                  <X size={18} />
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <p className="text-xs font-black text-slate-500 uppercase tracking-wider mb-2">荣誉文件</p>
                  <input
                    type="file"
                    onChange={(e) => setUploadFile(e.target.files?.[0] || null)}
                    className="block w-full rounded-xl border border-slate-200 p-3 text-sm text-slate-700 bg-slate-50"
                  />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <p className="text-xs font-black text-slate-500 uppercase tracking-wider mb-2">荣誉级别</p>
                    <select
                      value={honorLevel}
                      onChange={(e) => setHonorLevel(e.target.value)}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm font-semibold text-slate-700 bg-white"
                    >
                      <option value="校级">校级</option>
                      <option value="省级">省级</option>
                      <option value="国家级">国家级</option>
                    </select>
                  </div>
                  <div>
                    <p className="text-xs font-black text-slate-500 uppercase tracking-wider mb-2">荣誉分类</p>
                    <select
                      value={honorCategory}
                      onChange={(e) => setHonorCategory(e.target.value)}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm font-semibold text-slate-700 bg-white"
                    >
                      <option value="学术">学术</option>
                      <option value="体育">体育</option>
                      <option value="艺术">艺术</option>
                      <option value="社会实践">社会实践</option>
                    </select>
                  </div>
                </div>
                <div>
                  <p className="text-xs font-black text-slate-500 uppercase tracking-wider mb-2">获奖时间</p>
                  <input
                    type="month"
                    value={honorTimestamp}
                    onChange={(e) => setHonorTimestamp(e.target.value)}
                    className="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm font-semibold text-slate-700 bg-white"
                  />
                </div>
                <div>
                  <p className="text-xs font-black text-slate-500 uppercase tracking-wider mb-2">说明</p>
                  <textarea
                    value={honorDescription}
                    onChange={(e) => setHonorDescription(e.target.value)}
                    placeholder="可填写荣誉背景、获奖主体、项目简介等"
                    className="w-full h-24 rounded-xl border border-slate-200 px-3 py-2.5 text-sm text-slate-700 bg-slate-50 resize-none"
                  />
                </div>
              </div>

              <div className="flex justify-end gap-3">
                <button
                  onClick={() => setShowUploadModal(false)}
                  className="px-5 py-2.5 rounded-xl border border-slate-200 text-slate-600 font-bold hover:bg-slate-50"
                >
                  取消
                </button>
                <button
                  onClick={submitHonorUpload}
                  disabled={isUploadingHonor}
                  className="px-5 py-2.5 rounded-xl bg-blue-600 text-white font-black hover:bg-blue-700 disabled:bg-slate-300 disabled:cursor-not-allowed flex items-center gap-2"
                >
                  {isUploadingHonor ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
                  立即上传
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

const History = ({ size }: { size: number }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 8v4l3 3" />
        <circle cx="12" cy="12" r="10" />
    </svg>
);
