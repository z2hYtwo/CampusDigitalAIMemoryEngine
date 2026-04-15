import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import ReactECharts from 'echarts-for-react';
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
  Lightbulb,
  Camera,
  Award,
  ExternalLink,
  X
} from 'lucide-react';
import axios from 'axios';
import { captureEnhancedCameraFrame, DOCUMENT_GUIDE_RECT, evaluateCameraFrameQuality } from '../utils/cameraScan';

interface PrivateFile {
  fileName: string;
  objectName: string;
  uploadTime?: string;
  size?: string;
}

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

interface PrivateSpaceProps {
  user: { userId: string; username: string; role: string };
  onBack: () => void;
}

export function PrivateSpace({ user, onBack }: PrivateSpaceProps) {
  const [files, setFiles] = useState<PrivateFile[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [isCameraModalOpen, setIsCameraModalOpen] = useState(false);
  const [isCameraStarting, setIsCameraStarting] = useState(false);
  const [isCameraUploading, setIsCameraUploading] = useState(false);
  const [capturedCameraBlob, setCapturedCameraBlob] = useState<Blob | null>(null);
  const [capturedCameraPreviewUrl, setCapturedCameraPreviewUrl] = useState<string | null>(null);
  const [capturedCameraQualityText, setCapturedCameraQualityText] = useState<string | null>(null);
  const [capturedCameraQualityScore, setCapturedCameraQualityScore] = useState<number | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [previewFile, setPreviewFile] = useState<PrivateFile | null>(null);
  const [previewText, setPreviewText] = useState('');
  const [isPreviewTextLoading, setIsPreviewTextLoading] = useState(false);
  const [personalHonorTree, setPersonalHonorTree] = useState<HonorNode | null>(null);
  const [isPersonalHonorTreeLoading, setIsPersonalHonorTreeLoading] = useState(false);
  const [isPersonalHonorTreeOpen, setIsPersonalHonorTreeOpen] = useState(false);
  const [selectedHonorNode, setSelectedHonorNode] = useState<HonorNode | null>(null);
  const [honorUploadFile, setHonorUploadFile] = useState<File | null>(null);
  const [honorLevel, setHonorLevel] = useState('校级');
  const [honorCategory, setHonorCategory] = useState('学术');
  const [honorTimestamp, setHonorTimestamp] = useState('');
  const [honorDescription, setHonorDescription] = useState('');
  const [isUploadingHonor, setIsUploadingHonor] = useState(false);
  const [isDeletingHonor, setIsDeletingHonor] = useState(false);
  const cameraVideoRef = useRef<HTMLVideoElement | null>(null);
  const cameraCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const cameraStreamRef = useRef<MediaStream | null>(null);
  const cameraUploadAbortRef = useRef<AbortController | null>(null);
  const authHeaders = useMemo(() => ({
    'X-User-Id': user.userId,
    'X-User-Role': user.role
  }), [user.userId, user.role]);

  const getFileExtension = useCallback((value: string) => {
    const clean = value.split('?')[0];
    const index = clean.lastIndexOf('.');
    if (index < 0) return '';
    return clean.substring(index + 1).toLowerCase();
  }, []);

  const resolvePreviewKind = useCallback((fileName: string) => {
    const ext = getFileExtension(fileName);
    if (['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'svg'].includes(ext)) return 'image';
    if (['mp4', 'webm', 'ogg', 'mov', 'm4v', 'avi'].includes(ext)) return 'video';
    if (['mp3', 'wav', 'ogg', 'm4a', 'aac', 'flac'].includes(ext)) return 'audio';
    if (ext === 'pdf') return 'pdf';
    return 'text';
  }, [getFileExtension]);

  const buildAssetViewUrl = useCallback((objectName: string) => {
    const params = new URLSearchParams();
    params.set('objectName', objectName);
    params.set('userId', user.userId);
    params.set('role', user.role);
    return `/api/asset/view?${params.toString()}`;
  }, [user.role, user.userId]);

  const normalizeHonorNode = useCallback((honor: HonorNode): HonorNode => {
    if (honor.children && honor.children.length > 0) {
      return {
        ...honor,
        children: honor.children.map((child) => normalizeHonorNode(child))
      };
    }
    const text = `${honor.text || honor.name || ''}`.trim();
    const safeText = text.length > 160 ? `${text.slice(0, 160)}...` : text;
    return {
      ...honor,
      text: safeText || honor.name,
      name: (safeText || honor.name || '个人荣誉').slice(0, 30)
    };
  }, []);

  const countHonorLeafNodes = useCallback((node: HonorNode | null): number => {
    if (!node) return 0;
    if (!node.children || node.children.length === 0) return 1;
    return node.children.reduce((sum, child) => sum + countHonorLeafNodes(child), 0);
  }, []);

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

  const fetchPersonalHonorTree = useCallback(async () => {
    setIsPersonalHonorTreeLoading(true);
    try {
      const response = await axios.get<HonorNode[]>('/api/memory/honor-tree/personal', {
        headers: authHeaders,
        params: {
          userId: user.userId
        }
      });
      const root: HonorNode = {
        name: '个人荣誉树',
        type: 'year',
        children: (response.data || []).map((node) => normalizeHonorNode(node))
      };
      setPersonalHonorTree(root);
    } catch (err) {
      console.error('Fetch personal honor tree error:', err);
      setPersonalHonorTree({ name: '个人荣誉树', type: 'year', children: [] });
    } finally {
      setIsPersonalHonorTreeLoading(false);
    }
  }, [authHeaders, normalizeHonorNode, user.userId]);

  useEffect(() => {
    fetchPrivateFiles();
    fetchPersonalHonorTree();
  }, [fetchPersonalHonorTree, fetchPrivateFiles]);

  const stopCameraStream = useCallback(() => {
    if (cameraStreamRef.current) {
      cameraStreamRef.current.getTracks().forEach(track => track.stop());
      cameraStreamRef.current = null;
    }
    if (cameraVideoRef.current) {
      cameraVideoRef.current.srcObject = null;
    }
  }, []);

  const clearCapturedCameraPreview = useCallback(() => {
    setCapturedCameraBlob(null);
    setCapturedCameraQualityText(null);
    setCapturedCameraQualityScore(null);
    setCapturedCameraPreviewUrl((prev) => {
      if (prev) {
        URL.revokeObjectURL(prev);
      }
      return null;
    });
  }, []);

  useEffect(() => {
    if (!isCameraModalOpen) {
      clearCapturedCameraPreview();
      stopCameraStream();
      return;
    }

    let cancelled = false;

    const startCameraPreview = async () => {
      if (!navigator.mediaDevices?.getUserMedia) {
        alert('当前浏览器不支持摄像头功能');
        setIsCameraModalOpen(false);
        return;
      }

      setIsCameraStarting(true);
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: {
            facingMode: 'environment',
            width: { ideal: 2560 },
            height: { ideal: 1440 }
          },
          audio: false
        });
        if (cancelled) {
          stream.getTracks().forEach(track => track.stop());
          return;
        }
        cameraStreamRef.current = stream;
        if (cameraVideoRef.current) {
          cameraVideoRef.current.srcObject = stream;
          await cameraVideoRef.current.play().catch(() => undefined);
        }
      } catch (err) {
        const message = err instanceof Error ? err.message : '无法访问摄像头';
        alert(`摄像头启动失败：${message}`);
        setIsCameraModalOpen(false);
      } finally {
        if (!cancelled) {
          setIsCameraStarting(false);
        }
      }
    };

    startCameraPreview();

    return () => {
      cancelled = true;
      stopCameraStream();
    };
  }, [clearCapturedCameraPreview, isCameraModalOpen, stopCameraStream]);

  useEffect(() => {
    if (!isCameraModalOpen || capturedCameraPreviewUrl) {
      return;
    }
    const stream = cameraStreamRef.current;
    const video = cameraVideoRef.current;
    if (!stream || !video) {
      return;
    }
    if (video.srcObject !== stream) {
      video.srcObject = stream;
    }
    video.play().catch(() => undefined);
  }, [capturedCameraPreviewUrl, isCameraModalOpen]);

  useEffect(() => {
    return () => {
      if (cameraUploadAbortRef.current) {
        cameraUploadAbortRef.current.abort();
        cameraUploadAbortRef.current = null;
      }
      clearCapturedCameraPreview();
      stopCameraStream();
    };
  }, [clearCapturedCameraPreview, stopCameraStream]);

  const isUploadCanceledError = (error: unknown) => {
    const axiosError = error as { code?: string; name?: string };
    return axios.isCancel(error) || axiosError?.code === 'ERR_CANCELED' || axiosError?.name === 'CanceledError';
  };

  const cancelCameraUpload = () => {
    if (cameraUploadAbortRef.current) {
      cameraUploadAbortRef.current.abort();
      cameraUploadAbortRef.current = null;
    }
  };

  const handleCameraModalCancel = () => {
    const wasUploading = isCameraUploading;
    cancelCameraUpload();
    clearCapturedCameraPreview();
    setIsCameraModalOpen(false);
    if (wasUploading) {
      alert('已取消当前图片上传');
    }
  };

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
      await Promise.all([fetchPrivateFiles(), fetchPersonalHonorTree()]);
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
      await Promise.all([fetchPrivateFiles(), fetchPersonalHonorTree()]);
    } catch (err) {
      console.error('Delete error:', err);
      alert('删除失败');
    }
  };

  const handleCameraCapturePreview = async () => {
    if (user.role === 'admin') {
      alert('管理员账号不支持私人空间摄像头扫描');
      return;
    }

    const video = cameraVideoRef.current;
    const canvas = cameraCanvasRef.current;
    if (!video || !canvas) {
      alert('摄像头未就绪，请稍后重试');
      return;
    }

    try {
      const quality = evaluateCameraFrameQuality(video, DOCUMENT_GUIDE_RECT);
      const issueText = quality.issues.join('；');
      const qualityText = `质量评分 ${quality.score}/100`;
      if (!quality.passed) {
        setCapturedCameraBlob(null);
        setCapturedCameraPreviewUrl((prev) => {
          if (prev) {
            URL.revokeObjectURL(prev);
          }
          return null;
        });
        setCapturedCameraQualityText(null);
        setCapturedCameraQualityScore(null);
        alert(`${qualityText}，请重拍。${issueText ? `问题：${issueText}` : ''}`);
        return;
      }
      setCapturedCameraQualityText(`${qualityText}（可上传）`);
      setCapturedCameraQualityScore(quality.score);
    } catch (err) {
      const message = err instanceof Error ? err.message : '质量检测失败';
      alert(`拍照质量检测失败：${message}`);
      return;
    }

    let blob: Blob;
    try {
      blob = await captureEnhancedCameraFrame(video, canvas, {
        guideRect: DOCUMENT_GUIDE_RECT
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : '图像处理失败';
      alert(`拍照失败：${message}`);
      return;
    }
    const nextPreviewUrl = URL.createObjectURL(blob);
    setCapturedCameraBlob(blob);
    setCapturedCameraPreviewUrl((prev) => {
      if (prev) {
        URL.revokeObjectURL(prev);
      }
      return nextPreviewUrl;
    });
  };

  const handleCameraCaptureUpload = async () => {
    if (!capturedCameraBlob) {
      alert('请先拍照预览，再确认上传');
      return;
    }

    setIsCameraUploading(true);
    try {
      const controller = new AbortController();
      cameraUploadAbortRef.current = controller;
      const file = new File([capturedCameraBlob], `private-camera-scan-${Date.now()}.png`, { type: capturedCameraBlob.type || 'image/png' });
      const formData = new FormData();
      formData.append('file', file);
      formData.append('role', 'private');
      await axios.post('/api/asset/upload', formData, {
        headers: {
          ...authHeaders,
          'Content-Type': 'multipart/form-data'
        },
        params: {
          qualityScore: capturedCameraQualityScore ?? undefined
        },
        signal: controller.signal
      });
      clearCapturedCameraPreview();
      setIsCameraModalOpen(false);
      await fetchPrivateFiles();
      await fetchPersonalHonorTree();
    } catch (err) {
      if (isUploadCanceledError(err)) {
        alert('已取消当前图片上传');
        return;
      }
      console.error('Camera upload error:', err);
      alert('摄像头扫描上传失败，请稍后再试');
    } finally {
      cameraUploadAbortRef.current = null;
      setIsCameraUploading(false);
    }
  };

  const handlePersonalHonorUpload = async () => {
    if (!honorUploadFile) {
      alert('请先选择要上传的荣誉文件');
      return;
    }
    if (!honorLevel || !honorCategory) {
      alert('请完善荣誉级别与荣誉分类');
      return;
    }
    setIsUploadingHonor(true);
    try {
      const formData = new FormData();
      formData.append('file', honorUploadFile);
      formData.append('honorLevel', honorLevel);
      formData.append('honorCategory', honorCategory);
      formData.append('role', 'private');
      formData.append('userId', user.userId);
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
      const res = await axios.post('/api/asset/upload-honor', formData, {
        headers: authHeaders
      });
      alert(res.data?.message || '个人荣誉上传成功');
      setHonorUploadFile(null);
      setHonorDescription('');
      setHonorTimestamp('');
      setSelectedHonorNode(null);
      await Promise.all([fetchPrivateFiles(), fetchPersonalHonorTree()]);
    } catch (err) {
      const axiosError = err as { response?: { data?: { message?: string } }; message?: string };
      alert(axiosError.response?.data?.message || axiosError.message || '个人荣誉上传失败');
    } finally {
      setIsUploadingHonor(false);
    }
  };

  const handlePersonalHonorDelete = async () => {
    if (!selectedHonorNode?.objectName) {
      alert('请先在荣誉树中选择要删除的荣誉项');
      return;
    }
    if (!confirm('确定删除这条个人荣誉记录及其原件吗？')) return;
    setIsDeletingHonor(true);
    try {
      await axios.delete('/api/asset/delete', {
        headers: authHeaders,
        params: { objectName: selectedHonorNode.objectName }
      });
      alert('个人荣誉已删除');
      setSelectedHonorNode(null);
      await Promise.all([fetchPrivateFiles(), fetchPersonalHonorTree()]);
    } catch (err) {
      const axiosError = err as { response?: { data?: string }; message?: string };
      alert(axiosError.response?.data || axiosError.message || '删除个人荣誉失败');
    } finally {
      setIsDeletingHonor(false);
    }
  };

  const closeFilePreview = () => {
    setPreviewFile(null);
    setPreviewText('');
    setIsPreviewTextLoading(false);
  };

  const openFilePreview = async (file: PrivateFile) => {
    setPreviewFile(file);
    setPreviewText('');
    const kind = resolvePreviewKind(file.fileName);
    if (kind !== 'text') return;
    setIsPreviewTextLoading(true);
    try {
      const response = await axios.get('/api/asset/preview-text', {
        headers: authHeaders,
        params: {
          objectName: file.objectName,
          userId: user.userId,
          role: user.role
        },
        responseType: 'text'
      });
      setPreviewText(`${response.data || ''}`);
    } catch (err) {
      console.error('Preview text error:', err);
      setPreviewText('预览文本读取失败，请使用“新窗口打开”查看原文件。');
    } finally {
      setIsPreviewTextLoading(false);
    }
  };

  const getHonorTreeOption = () => {
    if (!personalHonorTree) return {};
    return {
      tooltip: {
        trigger: 'item',
        triggerOn: 'mousemove'
      },
      series: [
        {
          type: 'tree',
          data: [personalHonorTree],
          top: '5%',
          left: '8%',
          bottom: '5%',
          right: '20%',
          symbolSize: (_value: unknown, params: { data?: HonorNode }) => {
            const nodeType = params.data?.type;
            if (nodeType === 'year') return 16;
            if (nodeType === 'category') return 13;
            return 9;
          },
          label: {
            position: 'left',
            verticalAlign: 'middle',
            align: 'right',
            fontSize: 13,
            fontWeight: 'bold',
            color: '#475569'
          },
          leaves: {
            label: {
              position: 'right',
              verticalAlign: 'middle',
              align: 'left',
              color: '#334155',
              fontSize: 13,
              width: 220,
              overflow: 'truncate'
            }
          },
          expandAndCollapse: true,
          animationDuration: 500,
          animationDurationUpdate: 650,
          itemStyle: {
            color: (params: { data?: HonorNode }) => {
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
            curveness: 0.45
          }
        }
      ]
    };
  };

  const filteredFiles = files.filter(f => 
    f.fileName.toLowerCase().includes(searchQuery.toLowerCase())
  );
  const personalHonorCount = countHonorLeafNodes(personalHonorTree) - (personalHonorTree ? 1 : 0);

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
          <button
            type="button"
            onClick={() => setIsCameraModalOpen(true)}
            disabled={isCameraStarting || isCameraUploading}
            className="h-16 px-8 bg-emerald-600 text-white rounded-2xl text-lg font-black flex items-center gap-3 hover:bg-emerald-700 transition-all shadow-lg shadow-emerald-100 active:scale-95 disabled:opacity-60"
          >
            {(isCameraStarting || isCameraUploading) ? <Loader2 size={20} className="animate-spin" /> : <Camera size={20} />}
            <span>{isCameraUploading ? '扫描上传中...' : '摄像头扫描'}</span>
          </button>
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
              <button
                type="button"
                onClick={() => setIsPersonalHonorTreeOpen(true)}
                className="flex-1 bg-white p-7 rounded-3xl border border-slate-100 shadow-sm flex items-center gap-6 text-left hover:border-amber-200 hover:shadow-amber-50 hover:shadow-lg transition-all"
              >
                <div className="w-14 h-14 bg-amber-50 text-amber-600 rounded-xl flex items-center justify-center">
                  <Award size={26} />
                </div>
                <div>
                  <p className="text-sm font-black text-slate-400 uppercase">个人荣誉树</p>
                  <p className="text-2xl font-black text-slate-900">{Math.max(personalHonorCount, 0)} <span className="text-slate-400 text-base">项荣誉</span></p>
                </div>
              </button>
              
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
                          <button
                            type="button"
                            onClick={() => openFilePreview(file)}
                            className="text-lg font-black text-slate-900 truncate block hover:text-amber-500 transition-colors mb-1"
                          >
                            {file.fileName}
                          </button>
                          <div className="flex items-center gap-4 text-xs font-bold text-slate-400">
                            <span className="flex items-center gap-1"><Clock size={12} /> {file.uploadTime}</span>
                            <span>{file.size}</span>
                            <span className="px-2 py-0.5 bg-amber-50 text-amber-600 rounded-md text-[10px] uppercase font-black tracking-wider">Private</span>
                          </div>
                        </div>
                      </div>
                      
                      <div className="absolute top-6 right-6 flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
                        <button
                          onClick={() => openFilePreview(file)}
                          className="w-10 h-10 bg-blue-50 text-blue-600 rounded-xl flex items-center justify-center hover:bg-blue-100 transition-colors"
                        >
                          <ExternalLink size={16} />
                        </button>
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
      {previewFile && (
        <div className="fixed inset-0 z-[130] bg-slate-900/55 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-5xl rounded-3xl bg-white shadow-2xl border border-slate-200 overflow-hidden">
            <div className="px-6 py-5 border-b border-slate-100 flex items-center justify-between">
              <div className="min-w-0">
                <p className="text-base font-black text-slate-900 truncate">{previewFile.fileName}</p>
                <p className="text-xs font-bold text-slate-400 mt-1">私人空间文件预览</p>
              </div>
              <div className="flex items-center gap-2">
                <a
                  href={buildAssetViewUrl(previewFile.objectName)}
                  target="_blank"
                  rel="noreferrer"
                  className="h-10 px-4 rounded-xl bg-blue-50 hover:bg-blue-100 text-blue-700 text-sm font-bold flex items-center gap-2 transition-colors"
                >
                  <ExternalLink size={15} />
                  新窗口打开
                </a>
                <button
                  type="button"
                  onClick={closeFilePreview}
                  className="w-10 h-10 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-700 flex items-center justify-center transition-colors"
                >
                  <X size={16} />
                </button>
              </div>
            </div>
            <div className="p-6 min-h-[55vh] max-h-[75vh] overflow-auto bg-slate-50">
              {resolvePreviewKind(previewFile.fileName) === 'image' && (
                <img
                  src={buildAssetViewUrl(previewFile.objectName)}
                  alt={previewFile.fileName}
                  className="w-full max-h-[64vh] object-contain rounded-2xl bg-white border border-slate-200"
                />
              )}
              {resolvePreviewKind(previewFile.fileName) === 'video' && (
                <video
                  src={buildAssetViewUrl(previewFile.objectName)}
                  controls
                  className="w-full max-h-[64vh] rounded-2xl bg-black"
                />
              )}
              {resolvePreviewKind(previewFile.fileName) === 'audio' && (
                <div className="bg-white border border-slate-200 rounded-2xl p-8">
                  <audio src={buildAssetViewUrl(previewFile.objectName)} controls className="w-full" />
                </div>
              )}
              {resolvePreviewKind(previewFile.fileName) === 'pdf' && (
                <iframe
                  src={buildAssetViewUrl(previewFile.objectName)}
                  title={previewFile.fileName}
                  className="w-full h-[64vh] rounded-2xl bg-white border border-slate-200"
                />
              )}
              {resolvePreviewKind(previewFile.fileName) === 'text' && (
                <div className="bg-white border border-slate-200 rounded-2xl p-5">
                  {isPreviewTextLoading ? (
                    <div className="h-[50vh] flex items-center justify-center text-slate-400 gap-3 text-sm font-bold">
                      <Loader2 size={18} className="animate-spin" />
                      正在提取可预览文本...
                    </div>
                  ) : (
                    <pre className="whitespace-pre-wrap break-words text-sm leading-7 text-slate-700 min-h-[50vh]">{previewText || '暂无可展示的文本内容，请点击右上角“新窗口打开”查看原始文件。'}</pre>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
      {isPersonalHonorTreeOpen && (
        <div className="fixed inset-0 z-[125] bg-slate-900/55 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-6xl rounded-3xl bg-white shadow-2xl border border-slate-200 overflow-hidden">
            <div className="px-6 py-5 border-b border-slate-100 flex items-center justify-between">
              <div>
                <h3 className="text-xl font-black text-slate-900">个人荣誉树</h3>
                <p className="text-xs font-bold text-slate-400 mt-1">教师与学生可在私人空间查看自己的荣誉成长轨迹</p>
              </div>
              <button
                type="button"
                onClick={() => setIsPersonalHonorTreeOpen(false)}
                className="w-10 h-10 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-700 flex items-center justify-center transition-colors"
              >
                <X size={16} />
              </button>
            </div>
            <div className="p-6 grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_320px] gap-5 bg-slate-50">
              <div className="bg-white border border-slate-100 rounded-2xl min-h-[560px] p-4">
                {isPersonalHonorTreeLoading ? (
                  <div className="h-[520px] flex items-center justify-center text-slate-400 gap-3 text-sm font-bold">
                    <Loader2 size={18} className="animate-spin" />
                    正在加载个人荣誉树...
                  </div>
                ) : personalHonorTree?.children && personalHonorTree.children.length > 0 ? (
                  <ReactECharts
                    option={getHonorTreeOption()}
                    style={{ height: 540 }}
                    onEvents={{
                      click: (params: { data?: HonorNode }) => {
                        const node = params.data;
                        if (node && (!node.children || node.children.length === 0)) {
                          setSelectedHonorNode(node);
                        }
                      }
                    }}
                  />
                ) : (
                  <div className="h-[520px] flex items-center justify-center text-slate-400 text-sm font-bold">
                    暂无个人荣誉数据，上传荣誉资料后将自动生成荣誉树。
                  </div>
                )}
              </div>
              <div className="bg-white border border-slate-100 rounded-2xl p-5 space-y-3">
                <p className="text-xs font-black text-slate-400 uppercase tracking-widest">上传个人荣誉</p>
                <div className="space-y-3">
                  <input
                    type="file"
                    accept=".pdf,.jpg,.jpeg,.png,.webp,.doc,.docx"
                    onChange={(e) => setHonorUploadFile(e.target.files?.[0] || null)}
                    className="block w-full text-xs font-bold text-slate-500 file:mr-3 file:rounded-lg file:border-0 file:bg-slate-100 file:px-3 file:py-2 file:text-xs file:font-black file:text-slate-700 hover:file:bg-slate-200"
                  />
                  <div className="grid grid-cols-2 gap-2">
                    <select
                      value={honorLevel}
                      onChange={(e) => setHonorLevel(e.target.value)}
                      className="h-10 px-3 rounded-xl border border-slate-200 text-sm font-bold text-slate-700 bg-white"
                    >
                      <option value="校级">校级</option>
                      <option value="市级">市级</option>
                      <option value="省级">省级</option>
                      <option value="国家级">国家级</option>
                      <option value="国际级">国际级</option>
                    </select>
                    <select
                      value={honorCategory}
                      onChange={(e) => setHonorCategory(e.target.value)}
                      className="h-10 px-3 rounded-xl border border-slate-200 text-sm font-bold text-slate-700 bg-white"
                    >
                      <option value="学术">学术</option>
                      <option value="体育">体育</option>
                      <option value="艺术">艺术</option>
                      <option value="社会实践">社会实践</option>
                    </select>
                  </div>
                  <input
                    type="month"
                    value={honorTimestamp}
                    onChange={(e) => setHonorTimestamp(e.target.value)}
                    className="w-full h-10 px-3 rounded-xl border border-slate-200 text-sm font-bold text-slate-700 bg-white"
                  />
                  <textarea
                    value={honorDescription}
                    onChange={(e) => setHonorDescription(e.target.value)}
                    rows={3}
                    placeholder="可选：补充荣誉说明"
                    className="w-full rounded-xl border border-slate-200 p-3 text-sm font-bold text-slate-700 resize-none"
                  />
                  <button
                    type="button"
                    onClick={handlePersonalHonorUpload}
                    disabled={isUploadingHonor}
                    className="w-full h-10 rounded-xl bg-emerald-600 hover:bg-emerald-700 disabled:opacity-60 text-white text-sm font-black transition-colors flex items-center justify-center gap-2"
                  >
                    {isUploadingHonor ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />}
                    上传到个人荣誉树
                  </button>
                </div>
                <div className="h-px bg-slate-100 my-2" />
                <p className="text-xs font-black text-slate-400 uppercase tracking-widest">荣誉详情</p>
                {selectedHonorNode ? (
                  <>
                    <p className="text-lg font-black text-slate-900">{selectedHonorNode.name}</p>
                    <p className="text-sm font-bold text-slate-500 leading-7">{selectedHonorNode.text || '暂无详细描述'}</p>
                    <div className="text-xs font-bold text-slate-400 space-y-2 pt-2">
                      <p>类别：{selectedHonorNode.category || '未分类'}</p>
                      <p>级别：{selectedHonorNode.level || '未标注'}</p>
                      <p>时间：{selectedHonorNode.timestamp || '未知'}</p>
                    </div>
                    {selectedHonorNode.objectName && (
                      <div className="flex flex-wrap items-center gap-2">
                        <a
                          href={buildAssetViewUrl(selectedHonorNode.objectName)}
                          target="_blank"
                          rel="noreferrer"
                          className="inline-flex items-center gap-2 h-10 px-4 rounded-xl bg-amber-50 hover:bg-amber-100 text-amber-700 text-sm font-bold transition-colors"
                        >
                          <ExternalLink size={14} />
                          打开荣誉原件
                        </a>
                        <button
                          type="button"
                          onClick={handlePersonalHonorDelete}
                          disabled={isDeletingHonor}
                          className="inline-flex items-center gap-2 h-10 px-4 rounded-xl bg-rose-50 hover:bg-rose-100 disabled:opacity-60 text-rose-600 text-sm font-bold transition-colors"
                        >
                          {isDeletingHonor ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />}
                          删除该荣誉
                        </button>
                      </div>
                    )}
                  </>
                ) : (
                  <p className="text-sm font-bold text-slate-400 leading-7">点击荣誉树叶子节点可查看详细信息与原件链接。</p>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
      {isCameraModalOpen && (
        <div className="fixed inset-0 z-[120] bg-slate-900/55 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-4xl rounded-3xl bg-white shadow-2xl border border-slate-200 overflow-hidden">
            <div className="px-6 py-5 border-b border-slate-100 flex items-center justify-between">
              <h3 className="text-xl font-black text-slate-900">私人空间摄像头扫描</h3>
              <button
                type="button"
                onClick={handleCameraModalCancel}
                className="px-4 py-2 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-700 text-sm font-bold transition-colors"
              >
                关闭
              </button>
            </div>
            <div className="p-6 space-y-4">
              <div className="rounded-2xl overflow-hidden border border-slate-200 bg-slate-900 min-h-[360px] flex items-center justify-center relative">
                {capturedCameraPreviewUrl ? (
                  <img src={capturedCameraPreviewUrl} alt="扫描预览" className="w-full h-full object-contain bg-slate-950" />
                ) : (
                  <video
                    ref={cameraVideoRef}
                    autoPlay
                    playsInline
                    muted
                    className="w-full h-full object-contain bg-slate-950"
                  />
                )}
                {!capturedCameraPreviewUrl && (
                  <div className="pointer-events-none absolute inset-0">
                    <div className="absolute inset-0 bg-black/25" />
                    <div
                      className="absolute border-2 border-emerald-300 rounded-2xl shadow-[0_0_0_9999px_rgba(0,0,0,0.28)]"
                      style={{
                        left: `${DOCUMENT_GUIDE_RECT.x * 100}%`,
                        top: `${DOCUMENT_GUIDE_RECT.y * 100}%`,
                        width: `${DOCUMENT_GUIDE_RECT.width * 100}%`,
                        height: `${DOCUMENT_GUIDE_RECT.height * 100}%`
                      }}
                    />
                    <div className="absolute left-1/2 -translate-x-1/2 bottom-5 px-3 py-1.5 rounded-xl bg-slate-900/65 text-white text-xs font-bold">
                      请将资料完整放入绿色框后拍照
                    </div>
                  </div>
                )}
                {isCameraStarting && (
                  <div className="absolute inset-0 flex items-center justify-center text-white gap-3 text-sm font-bold bg-slate-900/60">
                    <Loader2 size={18} className="animate-spin" />
                    正在启动摄像头...
                  </div>
                )}
              </div>
              <p className="text-xs font-bold text-slate-500">
                建议：尽量正对文件拍摄，避免阴影和强反光，可降低 OCR 乱码概率。
              </p>
              {capturedCameraQualityText && (
                <p className="text-xs font-black text-emerald-700 bg-emerald-50 border border-emerald-100 rounded-xl px-3 py-2">
                  {capturedCameraQualityText}
                </p>
              )}
              <canvas ref={cameraCanvasRef} className="hidden" />
              <div className="flex items-center justify-end gap-3">
                <button
                  type="button"
                  onClick={handleCameraModalCancel}
                  className="px-5 h-11 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-700 text-sm font-bold transition-colors"
                >
                  取消
                </button>
                {capturedCameraPreviewUrl ? (
                  <>
                    <button
                      type="button"
                      onClick={clearCapturedCameraPreview}
                      disabled={isCameraUploading}
                      className="px-5 h-11 rounded-xl bg-slate-200 hover:bg-slate-300 disabled:opacity-60 text-slate-700 text-sm font-bold transition-colors"
                    >
                      重新拍照
                    </button>
                    <button
                      type="button"
                      onClick={handleCameraCaptureUpload}
                      disabled={isCameraUploading || isCameraStarting}
                      className="px-6 h-11 rounded-xl bg-emerald-600 hover:bg-emerald-700 disabled:opacity-60 text-white text-sm font-black transition-colors flex items-center gap-2"
                    >
                      {isCameraUploading ? <Loader2 size={16} className="animate-spin" /> : <Camera size={16} />}
                      确认上传到私人空间
                    </button>
                  </>
                ) : (
                  <button
                    type="button"
                    onClick={handleCameraCapturePreview}
                    disabled={isCameraUploading || isCameraStarting}
                    className="px-6 h-11 rounded-xl bg-emerald-600 hover:bg-emerald-700 disabled:opacity-60 text-white text-sm font-black transition-colors flex items-center gap-2"
                  >
                    <Camera size={16} />
                    拍照预览
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
