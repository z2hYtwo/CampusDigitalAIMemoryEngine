import { useState, useEffect, useRef, useMemo } from 'react'
import axios from 'axios'
import { Routes, Route, Link, Navigate, useNavigate } from 'react-router-dom'
import { 
  Database, Loader2, Sparkles, ChevronRight, FileText, ExternalLink, Lock,
  Video, Music, Link as LinkIcon, Info, Download, Image as ImageIcon, GraduationCap, Mic, Camera
} from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { PrivateSpace } from './components/PrivateSpace'
import { CampusHonorTree } from './components/CampusHonorTree'
import { ChartCard, type ChartConfig } from './components/ChartCard'
import { Navbar } from './components/Navbar'
import { cn } from './utils/cn'
import { captureEnhancedCameraFrame, DOCUMENT_GUIDE_RECT, evaluateCameraFrameQuality } from './utils/cameraScan'

/**
 * AI 执行链路展示组件 (State Management UI)
 */
function TraceDisplay({ trace }: { trace: NonNullable<AppSearchResponse['trace']> }) {
  const [isOpen, setIsOpen] = useState(false);
  const intentType = trace?.intentType || '--';
  const condensedQuery = trace?.condensedQuery || '--';
  const rawMatchCount = typeof trace?.rawMatchCount === 'number' ? trace.rawMatchCount : 0;
  const filteredMatchCount = typeof trace?.filteredMatchCount === 'number' ? trace.filteredMatchCount : 0;
  const thresholdText = typeof trace?.threshold === 'number' ? trace.threshold.toFixed(2) : '--';
  const routeConfidenceText = typeof trace?.routeConfidence === 'number' ? trace.routeConfidence.toFixed(2) : '--';
  const executionTimeText = typeof trace?.executionTimeMs === 'number' ? `${trace.executionTimeMs}ms` : '--';
  const toolSequence = trace?.toolSequence || '无';
  const scoreEntries = Object.entries(trace?.scoreSnapshot ?? {})
    .filter(([, score]) => typeof score === 'number' && Number.isFinite(score))
    .sort(([, a], [, b]) => b - a)
    .slice(0, 5);

  return (
    <div className="mt-4 px-4">
      <button 
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-2 text-xs font-black text-slate-400 hover:text-blue-600 transition-colors uppercase tracking-widest bg-slate-50 px-3 py-1.5 rounded-lg border border-slate-100"
      >
        <div className={cn("transition-transform duration-200", isOpen && "rotate-90")}>
          <ChevronRight size={14} />
        </div>
        <span>查看 AI 思考链路 (State Management)</span>
      </button>

      {isOpen && (
        <div className="mt-3 p-5 bg-slate-900 rounded-2xl border border-slate-800 text-slate-300 font-mono text-xs leading-relaxed shadow-2xl animate-in fade-in slide-in-from-top-2 duration-300">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="space-y-4">
              <div className="border-l-2 border-blue-500 pl-3">
                <p className="text-blue-400 font-bold mb-1 uppercase tracking-tighter">1. 意图识别 & 路由</p>
                <p><span className="text-slate-500">模式:</span> <span className="text-white font-bold">{intentType}</span></p>
                <p className="mt-1"><span className="text-slate-500">重写查询:</span> <span className="italic text-slate-400">"{condensedQuery}"</span></p>
              </div>

              <div className="border-l-2 border-emerald-500 pl-3">
                <p className="text-emerald-400 font-bold mb-1 uppercase tracking-tighter">2. 向量检索统计</p>
                <p><span className="text-slate-500">原始召回:</span> <span className="text-white">{rawMatchCount} 片段</span></p>
                <p><span className="text-slate-500">相似度阈值:</span> <span className="text-amber-400 font-bold">{thresholdText}</span></p>
                <p><span className="text-slate-500">最终入选:</span> <span className="text-emerald-400 font-bold">{filteredMatchCount} 片段</span></p>
              </div>
            </div>

            <div className="space-y-4">
              <div className="border-l-2 border-purple-500 pl-3">
                <p className="text-purple-400 font-bold mb-1 uppercase tracking-tighter">3. 文档打分快照 (Top 5)</p>
                <div className="space-y-1 mt-2 max-h-32 overflow-y-auto scrollbar-hide">
                  {scoreEntries.length > 0 ? scoreEntries
                    .map(([name, score]) => (
                      <div key={name} className="flex justify-between items-center bg-slate-800/50 px-2 py-1 rounded">
                        <span className="truncate mr-2 text-slate-400" title={name}>{name}</span>
                        <span className={cn(
                          "font-bold",
                          score > 0.6 ? "text-emerald-400" : score > 0.4 ? "text-amber-400" : "text-slate-500"
                        )}>{score.toFixed(3)}</span>
                      </div>
                    )) : (
                      <div className="text-slate-500">暂无评分数据</div>
                    )}
                </div>
              </div>

              <div className="border-l-2 border-rose-500 pl-3">
                <p className="text-rose-400 font-bold mb-1 uppercase tracking-tighter">4. 性能监控</p>
                <p><span className="text-slate-500">总执行耗时:</span> <span className="text-white font-bold">{executionTimeText}</span></p>
                <p><span className="text-slate-500">路由置信度:</span> <span className="text-white font-bold">{routeConfidenceText}</span></p>
                <p className="truncate" title={toolSequence}><span className="text-slate-500">工具链路:</span> <span className="text-slate-300">{toolSequence}</span></p>
              </div>
            </div>
          </div>
          
          <div className="mt-4 pt-4 border-t border-slate-800 text-[10px] text-slate-600 flex justify-between">
            <span>CDAME LOGS / SESSION_PERSISTENCE: ENABLED</span>
            <span className="animate-pulse text-emerald-900">● AGENT_STATUS: ONLINE</span>
          </div>
        </div>
      )}
    </div>
  );
}

// 定义一个别名方便在 TraceDisplay 中引用，避免 App 内部定义的 SearchResponse 作用域问题
type AppSearchResponse = {
  answer?: string;
  memories: string[];
  needsClarification: boolean;
  clarificationSuggestions: string[];
  relevantFiles?: {
    fileName: string;
    objectName: string;
    url: string;
    sourceType?: 'public' | 'private' | 'official' | 'multimedia' | 'link';
    isPrivate?: boolean;
  }[];
  trace?: {
    originalQuery: string;
    condensedQuery: string;
    intentType: string;
    threshold: number;
    rawMatchCount: number;
    filteredMatchCount: number;
    rerankTopK?: number;
    finalTopK?: number;
    toolSequence?: string;
    routeConfidence?: number;
    scoreSnapshot: Record<string, number>;
    executionTimeMs: number;
  };
};

type VoiceInputResponse = {
  status: string
  recognizedText?: string
  recognizedLanguage?: string
  answer?: string
  provider?: string
  message?: string
}

/**
 * 侧边栏预览组件 (PDF, Video, Audio, Link)
 */
function PreviewSidebar({ 
  file, 
  onClose,
  getDisplayFileName,
  requestUserId,
  requestRole
}: { 
  file: NonNullable<AppSearchResponse['relevantFiles']>[number], 
  onClose: () => void,
  getDisplayFileName: (f?: string, o?: string) => string,
  requestUserId?: string,
  requestRole?: string
}) {
  const [textCache, setTextCache] = useState<Record<string, string>>({});
  const [slideCountCache, setSlideCountCache] = useState<Record<string, number>>({});
  const [slideInitialCountCache, setSlideInitialCountCache] = useState<Record<string, number>>({});
  const [slideVisibleCountCache, setSlideVisibleCountCache] = useState<Record<string, number>>({});
  const loadMoreRef = useRef<HTMLDivElement | null>(null);

  const isVideo = (name?: string) => {
    if (!name) return false;
    return /\.(mp4|avi|mov|webm)$/i.test(name);
  };
  const isAudio = (name?: string) => {
    if (!name) return false;
    return /\.(mp3|wav|ogg)$/i.test(name);
  };
  const isPDF = (name?: string) => {
    if (!name) return false;
    return /\.pdf$/i.test(name);
  };
  const isOffice = (name?: string) => {
    if (!name) return false;
    return /\.(docx?|xlsx?|pptx?)$/i.test(name);
  };
  const isPowerPoint = (name?: string) => {
    if (!name) return false;
    return /\.(ppt|pptx)$/i.test(name);
  };
  const isText = (name?: string) => {
    if (!name) return false;
    return /\.(txt|md|json|csv|log)$/i.test(name);
  };
  const isImage = (name?: string) => {
    if (!name) return false;
    return /\.(jpg|jpeg|png|webp|gif|bmp)$/i.test(name);
  };
  const detectName = file.fileName || file.objectName || '';
  const isLink = file.sourceType === 'link';
  const fileName = getDisplayFileName(file.fileName, file.objectName);
  const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
  const appendAccessContext = (url: string) => {
    if (!url) return '';
    const hasUserId = /[?&]userId=/.test(url);
    const hasRole = /[?&]role=/.test(url);
    const parts: string[] = [];
    if (!hasUserId && requestUserId) {
      parts.push(`userId=${encodeURIComponent(requestUserId)}`);
    }
    if (!hasRole && requestRole) {
      parts.push(`role=${encodeURIComponent(requestRole)}`);
    }
    if (!parts.length) return url;
    const joiner = url.includes('?') ? '&' : '?';
    return `${url}${joiner}${parts.join('&')}`;
  };
  const baseAssetUrl = file.objectName
    ? `/api/asset/view?objectName=${encodeURIComponent(file.objectName)}`
    : '';
  const viewUrl = isLink
    ? (file.url || '')
    : appendAccessContext((file.url && file.url.trim()) ? file.url : baseAssetUrl);
  
  const getAbsoluteUrl = (url?: string) => {
    if (!url) return '';
    if (url.startsWith('http')) return url;
    const origin = window.location.origin;
    const cleanUrl = url.startsWith('/') ? url : `/${url}`;
    return `${origin}${cleanUrl}`;
  };

  const getOfficeTextPreviewUrl = (url?: string) => {
    if (!url) return '';
    if (url.includes('/api/asset/view')) {
      return url.replace('/api/asset/view', '/api/asset/preview-text');
    }
    return url;
  };
  const getOfficeSlidesPreviewUrl = (url?: string) => {
    if (!url) return '';
    const base = url.includes('/api/asset/view')
      ? url.replace('/api/asset/view', '/api/asset/preview-slides')
      : url;
    const joiner = base.includes('?') ? '&' : '?';
    return `${base}${joiner}maxSlides=3&width=640`;
  };
  const getOfficeSlideImageBaseUrl = (url?: string) => {
    if (!url) return '';
    const base = url.includes('/api/asset/view')
      ? url.replace('/api/asset/view', '/api/asset/preview-slide-image')
      : url;
    const joiner = base.includes('?') ? '&' : '?';
    return `${base}${joiner}width=640`;
  };

  const textPreviewUrl = isText(detectName)
    ? viewUrl
    : (isOffice(detectName) && isLocal && !isPowerPoint(detectName) ? getOfficeTextPreviewUrl(viewUrl) : '');
  const slidesPreviewUrl = (isOffice(detectName) && isLocal && isPowerPoint(detectName))
    ? getOfficeSlidesPreviewUrl(viewUrl)
    : '';
  const slideImageBaseUrl = (isOffice(detectName) && isLocal && isPowerPoint(detectName))
    ? getOfficeSlideImageBaseUrl(viewUrl)
    : '';
  const textContent = textPreviewUrl ? (textCache[textPreviewUrl] ?? null) : null;
  const slideCount = slidesPreviewUrl ? (slideCountCache[slidesPreviewUrl] ?? null) : null;
  const slideInitialCount = slidesPreviewUrl ? (slideInitialCountCache[slidesPreviewUrl] ?? null) : null;
  const slideVisibleCount = slidesPreviewUrl
    ? (slideVisibleCountCache[slidesPreviewUrl] ?? (slideInitialCount ?? 0))
    : 0;
  const loadingText = !!textPreviewUrl && textContent === null;
  const loadingSlides = !!slidesPreviewUrl && slideCount === null;

  useEffect(() => {
    if (!textPreviewUrl || textCache[textPreviewUrl] != null) return;
    fetch(textPreviewUrl)
      .then(res => res.text())
      .then(text => {
        setTextCache(prev => ({ ...prev, [textPreviewUrl]: text }));
      })
      .catch(err => {
        console.error('Failed to fetch text content:', err);
        const fallback = isText(detectName) ? '无法加载文本内容' : '无法加载文档预览文本';
        setTextCache(prev => ({ ...prev, [textPreviewUrl]: fallback }));
      });
  }, [textPreviewUrl, textCache, detectName]);

  useEffect(() => {
    if (!slidesPreviewUrl) return;
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), 20000);
    fetch(slidesPreviewUrl, { signal: controller.signal, cache: 'no-store' })
      .then(async res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        const totalRaw = data?.total ?? data?.count ?? 0;
        const totalNum = typeof totalRaw === 'number' ? totalRaw : Number(totalRaw);
        const total = Number.isFinite(totalNum) ? Math.max(0, Math.floor(totalNum)) : 0;
        const initialCountRaw = typeof data?.initialCount === 'number' && Number.isFinite(data.initialCount)
          ? Math.max(0, Math.floor(data.initialCount))
          : 0;
        const initialCount = Math.min(Math.max(initialCountRaw, 0), total);
        setSlideCountCache(prev => ({ ...prev, [slidesPreviewUrl]: total }));
        setSlideInitialCountCache(prev => ({ ...prev, [slidesPreviewUrl]: initialCount }));
        setSlideVisibleCountCache(prev => ({
          ...prev,
          [slidesPreviewUrl]: Math.max(prev[slidesPreviewUrl] ?? 0, initialCount)
        }));
      })
      .catch(err => {
        console.error('Failed to fetch ppt slides preview:', err);
        setSlideCountCache(prev => ({ ...prev, [slidesPreviewUrl]: 0 }));
        setSlideInitialCountCache(prev => ({ ...prev, [slidesPreviewUrl]: 0 }));
        setSlideVisibleCountCache(prev => ({ ...prev, [slidesPreviewUrl]: 0 }));
      })
      .finally(() => {
        window.clearTimeout(timeoutId);
      });
    return () => {
      window.clearTimeout(timeoutId);
      controller.abort();
    };
  }, [slidesPreviewUrl]);

  useEffect(() => {
    if (!slidesPreviewUrl || !isPowerPoint(detectName)) return;
    if (!slideCount || slideVisibleCount >= slideCount) return;
    const node = loadMoreRef.current;
    if (!node) return;
    const observer = new IntersectionObserver((entries) => {
      const hit = entries.some(entry => entry.isIntersecting);
      if (!hit) return;
      setSlideVisibleCountCache(prev => {
        const current = prev[slidesPreviewUrl] ?? (slideInitialCountCache[slidesPreviewUrl] ?? 0);
        const next = Math.min(current + 4, slideCount);
        if (next === current) return prev;
        return { ...prev, [slidesPreviewUrl]: next };
      });
    }, { root: null, threshold: 0.1 });
    observer.observe(node);
    return () => observer.disconnect();
  }, [slidesPreviewUrl, slideCount, slideVisibleCount, detectName, slideInitialCountCache]);

  return (
    <div className="fixed inset-y-0 right-0 w-full md:w-[860px] lg:w-[1040px] xl:w-[1200px] 2xl:w-[1360px] bg-white shadow-2xl z-[100] border-l border-slate-200 flex flex-col animate-in slide-in-from-right duration-300">
      {/* 头部 */}
      <div className="h-16 border-b border-slate-100 flex items-center justify-between px-6 bg-slate-50/50">
        <div className="flex items-center gap-3 overflow-hidden">
          <div className="p-2 bg-blue-100 text-blue-600 rounded-lg shrink-0">
            {isLink ? <LinkIcon size={18} /> : 
             isPDF(detectName) ? <FileText size={18} /> : 
             isOffice(detectName) ? <FileText size={18} className="text-blue-700" /> :
             isImage(detectName) ? <ImageIcon size={18} /> :
             isVideo(detectName) ? <Video size={18} /> : 
             isAudio(detectName) ? <Music size={18} /> : 
             <FileText size={18} />}
          </div>
          <h3 className="font-black text-slate-800 truncate" title={fileName}>
            {fileName}
          </h3>
        </div>
        <button 
          onClick={onClose}
          className="p-2 hover:bg-slate-200 rounded-full transition-colors text-slate-400 hover:text-slate-800"
        >
          <ChevronRight size={24} className="rotate-0" />
        </button>
      </div>

      {/* 内容区 */}
      <div className="flex-1 bg-slate-100 overflow-hidden relative">
        {isPDF(detectName) ? (
          <iframe 
            src={`${viewUrl}#toolbar=0&navpanes=0&zoom=page-width`} 
            className="w-full h-full border-none bg-white"
            title="PDF Preview"
          />
        ) : isOffice(detectName) ? (
          isLocal ? (
            <div className="w-full h-full bg-white p-8 overflow-auto">
              <div className="p-4 bg-amber-50 text-amber-700 rounded-2xl mb-5 border border-amber-100 flex items-center gap-3">
                <Info size={20} />
                <p className="text-sm font-bold">
                  {isPowerPoint(detectName) ? '当前为本地模式，已切换为幻灯片预览' : '当前为本地模式，已切换为文本预览'}
                </p>
              </div>
              {isPowerPoint(detectName) ? (
                loadingSlides ? (
                  <div className="flex items-center justify-center h-[60vh]">
                    <Loader2 className="animate-spin text-blue-600" size={32} />
                  </div>
                ) : (slideCount && slideCount > 0 ? (
                  <div className="space-y-6">
                    {Array.from({ length: slideVisibleCount }).map((_, index) => (
                      <div key={`${file.objectName}-${index}`} className="bg-slate-50 border border-slate-200 rounded-2xl p-3">
                        <p className="text-xs font-black text-slate-400 mb-2 uppercase tracking-widest">第 {index + 1} 页</p>
                        <img
                          src={`${slideImageBaseUrl}&slideIndex=${index}`}
                          loading="lazy"
                          alt={`slide-${index + 1}`}
                          className="w-full rounded-lg border border-slate-200 bg-white"
                        />
                      </div>
                    ))}
                    {slideVisibleCount < slideCount && (
                      <div ref={loadMoreRef} className="text-center text-xs font-bold text-slate-400 py-2">
                        向下滚动继续加载（已加载 {slideVisibleCount}/{slideCount} 页）
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="text-slate-500 text-sm font-bold">未能生成幻灯片预览，请下载原文件查看。</div>
                ))
              ) : (
                loadingText ? (
                  <div className="flex items-center justify-center h-[60vh]">
                    <Loader2 className="animate-spin text-blue-600" size={32} />
                  </div>
                ) : (
                  <pre className="whitespace-pre-wrap text-[15px] text-slate-700 leading-7">
                    {textContent}
                  </pre>
                )
              )}
              <div className="mt-6">
                <a
                  href={viewUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-2 px-6 py-3 bg-blue-600 text-white rounded-xl font-bold hover:bg-blue-700 transition-all"
                >
                  <Download size={16} /> 下载原文件
                </a>
              </div>
            </div>
          ) : (
            <iframe 
              src={`https://view.officeapps.live.com/op/view.aspx?src=${encodeURIComponent(getAbsoluteUrl(viewUrl))}`} 
              className="w-full h-full border-none bg-white"
              title="Office Preview"
            />
          )
        ) : isText(detectName) ? (
          <div className="w-full h-full bg-white p-8 overflow-auto">
            {loadingText ? (
              <div className="flex items-center justify-center h-full">
                <Loader2 className="animate-spin text-blue-600" size={32} />
              </div>
            ) : (
              <pre className="whitespace-pre-wrap font-mono text-sm text-slate-700 leading-relaxed">
                {textContent}
              </pre>
            )}
          </div>
        ) : isImage(detectName) ? (
          <div className="w-full h-full flex items-center justify-center bg-slate-50 p-2">
            <img
              src={viewUrl}
              alt={fileName}
              className="w-full h-full object-contain"
            />
          </div>
        ) : isVideo(detectName) ? (
          <div className="w-full h-full flex items-center justify-center bg-black">
            <video 
              src={viewUrl} 
              controls 
              autoPlay
              className="max-w-full max-h-full"
            />
          </div>
        ) : isAudio(detectName) ? (
          <div className="w-full h-full flex flex-col items-center justify-center bg-slate-50 p-10">
            <div className="w-32 h-32 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center mb-8 animate-pulse">
              <Music size={48} />
            </div>
            <audio src={viewUrl} controls className="w-full" />
            <p className="mt-4 text-slate-400 font-bold uppercase tracking-widest text-xs">正在播放音频资产</p>
          </div>
        ) : isLink ? (
          <iframe 
            src={viewUrl} 
            className="w-full h-full border-none bg-white"
            title="External Link Preview"
            sandbox="allow-scripts allow-same-origin allow-popups"
          />
        ) : (
          <div className="w-full h-full flex flex-col items-center justify-center text-slate-400 p-10">
            <Info size={48} className="mb-4 opacity-20" />
            <p className="font-bold">该文件类型暂不支持直接预览</p>
            <a 
              href={viewUrl} 
              target="_blank" 
              rel="noopener noreferrer"
              className="mt-4 px-6 py-2 bg-blue-600 text-white rounded-xl font-bold hover:bg-blue-700 transition-all"
            >
              下载并查看
            </a>
          </div>
        )}
      </div>

      {/* 底部信息 */}
      <div className="p-6 border-t border-slate-100 bg-white">
        <div className="flex items-center justify-between mb-4">
          <span className="text-xs font-black text-slate-400 uppercase tracking-widest">资源详情</span>
          {file.isPrivate && (
            <span className="flex items-center gap-1 text-[10px] font-black bg-amber-100 text-amber-600 px-2 py-0.5 rounded uppercase">
              <Lock size={10} /> 私有资产
            </span>
          )}
        </div>
        <div className="space-y-2">
          <p className="text-sm text-slate-600 leading-relaxed">
            <span className="font-bold text-slate-400 mr-2">来源类型:</span>
            {file.sourceType === 'official' ? '官方档案' : file.sourceType === 'multimedia' ? '多媒体素材' : file.sourceType === 'link' ? '外部链接' : '公共文档'}
          </p>
          <p className="text-sm text-slate-600 truncate">
            <span className="font-bold text-slate-400 mr-2">原始路径:</span>
            {file.objectName}
          </p>
        </div>
      </div>
    </div>
  );
}

type SearchResponse = AppSearchResponse
type UserRole = 'student' | 'teacher' | 'admin'
type AuthUser = { userId: string; username: string; role: UserRole }

/**
 * 通用布局组件 (Shared Layout)
 */
function Layout({
  user,
  onLogout,
  onLogin,
  onSync,
  onUpload,
  onAddLink,
  onCameraScan,
  isSyncing,
  isUploading,
  isCameraScanning,
  children
}: {
  user: AuthUser | null;
  onLogout: () => void;
  onLogin: () => void;
  onSync: () => void;
  onUpload: (e: React.ChangeEvent<HTMLInputElement>) => void;
  onAddLink: () => void;
  onCameraScan: () => void;
  isSyncing: boolean;
  isUploading: boolean;
  isCameraScanning: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen bg-[#F8FAFC] text-slate-900 font-sans selection:bg-blue-100 selection:text-blue-700">
      <Navbar
        user={user}
        onLogout={onLogout}
        onLogin={onLogin}
        onSync={onSync}
        onUpload={onUpload}
        onAddLink={onAddLink}
        onCameraScan={onCameraScan}
        isSyncing={isSyncing}
        isUploading={isUploading}
        isCameraScanning={isCameraScanning}
      />
      <main className="max-w-[1680px] mx-auto px-4 sm:px-6 lg:px-8 py-4">
        <div className="bg-white rounded-[2rem] border border-slate-200/70 shadow-lg shadow-slate-200/20 overflow-hidden min-h-[calc(100vh-7.5rem)]">
          <div className="h-full w-full">{children}</div>
        </div>
      </main>
      {/* 装饰性背景元素 */}
      <div className="fixed top-0 right-0 -z-10 w-[500px] h-[500px] bg-blue-50/50 blur-[120px] rounded-full pointer-events-none" />
      <div className="fixed bottom-0 left-0 -z-10 w-[500px] h-[500px] bg-indigo-50/50 blur-[120px] rounded-full pointer-events-none" />
    </div>
  );
}

/**
 * 页面占位组件
 */
function PlaceholderPage({ title, description }: { title: string, description: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-32 px-10 text-center">
      <div className="w-24 h-24 bg-blue-50 text-blue-600 rounded-[2rem] flex items-center justify-center mb-8 shadow-inner shadow-blue-100">
        <Sparkles size={40} />
      </div>
      <h2 className="text-4xl font-black text-slate-900 tracking-tight mb-4">{title}</h2>
      <p className="text-slate-500 text-xl font-medium max-w-md leading-relaxed">{description}</p>
      <div className="mt-12 flex gap-4">
        <div className="px-6 py-3 bg-slate-900 text-white rounded-2xl font-bold shadow-xl shadow-slate-200">正在建设中...</div>
        <Link to="/" className="px-6 py-3 bg-white border border-slate-200 text-slate-600 rounded-2xl font-bold hover:bg-slate-50 transition-colors">返回首页</Link>
      </div>
    </div>
  );
}

function ShowcasePage({
  title,
  metrics,
  items,
  accent,
  primaryPath,
  primaryText
}: {
  title: string;
  metrics: { label: string; value: string }[];
  items: { title: string; desc: string; tag: string }[];
  accent: 'blue' | 'emerald' | 'amber' | 'violet';
  primaryPath: string;
  primaryText: string;
}) {
  const accentStyle = accent === 'blue'
    ? 'from-blue-50 to-indigo-50 border-blue-100 text-blue-600'
    : accent === 'emerald'
      ? 'from-emerald-50 to-cyan-50 border-emerald-100 text-emerald-600'
      : accent === 'amber'
        ? 'from-amber-50 to-orange-50 border-amber-100 text-amber-600'
        : 'from-violet-50 to-purple-50 border-violet-100 text-violet-600'

  return (
    <div className="px-8 py-8 md:px-12 md:py-10 space-y-8">
      <div className={`rounded-3xl border bg-gradient-to-br ${accentStyle} p-8`}>
        <h2 className="text-4xl font-black text-slate-900">{title}</h2>
        <div className="mt-6 flex flex-wrap gap-3">
          <Link to={primaryPath} className="px-5 py-2.5 rounded-xl bg-slate-900 text-white font-black hover:bg-slate-800 transition-colors">
            {primaryText}
          </Link>
          <Link to="/" className="px-5 py-2.5 rounded-xl bg-white border border-slate-200 text-slate-600 font-bold hover:bg-slate-50 transition-colors">
            返回智能问答
          </Link>
        </div>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {metrics.map((metric) => (
          <div key={metric.label} className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
            <p className="text-xs font-black uppercase tracking-widest text-slate-400">{metric.label}</p>
            <p className="mt-2 text-2xl font-black text-slate-900">{metric.value}</p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {items.map((item) => (
          <div key={item.title} className="rounded-2xl border border-slate-100 bg-white p-6 hover:shadow-lg transition-all">
            <p className="text-xs font-black uppercase tracking-widest text-slate-400">{item.tag}</p>
            <h3 className="mt-2 text-xl font-black text-slate-900">{item.title}</h3>
          </div>
        ))}
      </div>
    </div>
  )
}

type ScoreStatisticsResponse = {
  totalStudents: number
  totalScoreRecords: number
  excellentRate: number
  warningStudents: number
  scope?: 'student' | 'global'
  studentId?: string
  lineChartConfig?: ChartConfig
  pieChartConfig?: ChartConfig
  error?: string
}

type StudentInsightResponse = {
  scope: 'student'
  studentId: string
  studentName: string
  primaryFocus: string
  requestedByRole?: UserRole
  queryKeyword?: string
  overview: ScoreStatisticsResponse
  radarData: Record<string, number>
  radarChartConfig?: ChartConfig
  trendChartConfig?: ChartConfig
  weakCourseChartConfig?: ChartConfig
  weakCourses?: Array<{ course_name: string; score: number; credits: number; gpa: number }>
  topCourses?: Array<{ course_name: string; score: number; credits: number; gpa: number }>
  aiSuggestions?: string[]
  error?: string
}

type MajorStatisticsResponse = {
  uniqueMajorCount: number
  departmentCount: number
  directionCount: number
  studentRowsWithMajor: number
  error?: string
}

type HistoryAsset = {
  fileName: string
  objectName: string
  uploadTime?: string
  size?: string
  sourceType?: string
}

type HistorySection = 'timeline' | 'archives' | 'media'

const IMAGE_EXT = new Set(['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp'])
const VIDEO_EXT = new Set(['mp4', 'avi', 'mov', 'webm'])
const AUDIO_EXT = new Set(['mp3', 'wav', 'ogg', 'flac', 'm4a'])
const DOC_EXT = new Set(['pdf', 'doc', 'docx', 'ppt', 'pptx', 'txt', 'md', 'csv', 'json', 'xls', 'xlsx'])

function getExtension(name?: string) {
  if (!name) return ''
  const normalized = name.toLowerCase()
  const idx = normalized.lastIndexOf('.')
  return idx >= 0 ? normalized.slice(idx + 1) : ''
}

function parseUploadTime(time?: string) {
  if (!time) return 0
  const trimmed = time.trim()
  const matched = trimmed.match(/^(\d{4})-(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{1,2})$/)
  if (matched) {
    const [, y, m, d, hh, mm] = matched
    const localTs = new Date(
      Number(y),
      Number(m) - 1,
      Number(d),
      Number(hh),
      Number(mm),
      0,
      0
    ).getTime()
    if (Number.isFinite(localTs)) return localTs
  }
  const normalized = trimmed.replace(' ', 'T')
  const parsed = new Date(normalized).getTime()
  return Number.isFinite(parsed) ? parsed : 0
}

function parseSizeToBytes(size?: string) {
  if (!size) return 0
  const text = size.trim().toUpperCase()
  const num = Number.parseFloat(text)
  if (!Number.isFinite(num)) return 0
  if (text.endsWith('GB')) return num * 1024 * 1024 * 1024
  if (text.endsWith('MB')) return num * 1024 * 1024
  if (text.endsWith('KB')) return num * 1024
  return num
}

function formatBytes(bytes: number) {
  if (bytes <= 0) return '0 B'
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${Math.floor(bytes)} B`
}

function getAssetTypeTag(ext: string) {
  if (IMAGE_EXT.has(ext)) return '图片'
  if (VIDEO_EXT.has(ext)) return '视频'
  if (AUDIO_EXT.has(ext)) return '音频'
  if (DOC_EXT.has(ext)) return '文档'
  return '其他'
}

function compareAssetsByTimeDesc(a: HistoryAsset, b: HistoryAsset) {
  const t1 = parseUploadTime(a.uploadTime)
  const t2 = parseUploadTime(b.uploadTime)
  if (t1 !== t2) return t2 - t1
  const text1 = (a.uploadTime || '').trim()
  const text2 = (b.uploadTime || '').trim()
  const textCompare = text2.localeCompare(text1)
  if (textCompare !== 0) return textCompare
  return (b.objectName || '').localeCompare(a.objectName || '')
}

function HistoryHubPage({
  section,
  title,
  accent,
  primaryPath,
  primaryText,
  authHeaders,
  onOpenPreview
}: {
  section: HistorySection
  title: string
  accent: 'blue' | 'violet' | 'amber'
  primaryPath: string
  primaryText: string
  authHeaders: Record<string, string>
  onOpenPreview: (file: NonNullable<AppSearchResponse['relevantFiles']>[number]) => void
}) {
  const [assets, setAssets] = useState<HistoryAsset[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let mounted = true
    const loadAssets = async () => {
      try {
        setLoading(true)
        setError('')
        const response = await axios.get<HistoryAsset[]>('/api/asset/list', {
          headers: authHeaders,
          params: { role: 'all' }
        })
        if (!mounted) return
        const list = Array.isArray(response.data) ? response.data : []
        const normalized = list
          .filter(item => item && typeof item.fileName === 'string' && typeof item.objectName === 'string')
          .filter(item => item.sourceType !== 'private')
          .map(item => ({
            ...item,
            uploadTime: item.uploadTime || '',
            size: item.size || ''
          }))
          .sort(compareAssetsByTimeDesc)
        setAssets(normalized)
      } catch {
        if (!mounted) return
        setError('资产数据加载失败，请检查后端服务连接。')
      } finally {
        if (mounted) setLoading(false)
      }
    }
    loadAssets()
    return () => {
      mounted = false
    }
  }, [authHeaders])

  const computed = useMemo(() => {
    const images = assets.filter(a => IMAGE_EXT.has(getExtension(a.fileName)))
    const videos = assets.filter(a => VIDEO_EXT.has(getExtension(a.fileName)))
    const audios = assets.filter(a => AUDIO_EXT.has(getExtension(a.fileName)))
    const docs = assets.filter(a => DOC_EXT.has(getExtension(a.fileName)))
    const knownMedia = [...images, ...videos, ...audios]
    const mediaObjectNames = new Set(knownMedia.map(item => item.objectName))
    const otherMedia = assets.filter(a => a.sourceType === 'multimedia' && !mediaObjectNames.has(a.objectName))
    const media = [...knownMedia, ...otherMedia]
    const totalBytes = assets.reduce((acc, cur) => acc + parseSizeToBytes(cur.size), 0)
    const timeline = assets
      .slice(0, 10)
      .map(a => {
        const dateText = a.uploadTime || '未标注时间'
        const key = parseUploadTime(a.uploadTime)
        const dt = key > 0 ? new Date(key) : null
        const period = dt ? `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}` : '未标注'
        return {
          objectName: a.objectName,
          period,
          dateText,
          title: a.fileName,
          desc: `${getAssetTypeTag(getExtension(a.fileName))} · ${a.size || '未知大小'}`
        }
      })
    const latestThree = assets.slice(0, 3)
    return { images, videos, audios, docs, media, totalBytes, timeline, latestThree }
  }, [assets])

  const accentClass = accent === 'blue'
    ? 'from-blue-50 to-indigo-50 border-blue-100'
    : accent === 'violet'
      ? 'from-violet-50 to-purple-50 border-violet-100'
      : 'from-amber-50 to-orange-50 border-amber-100'
  const badgeClass = accent === 'blue'
    ? 'bg-blue-100 text-blue-700'
    : accent === 'violet'
      ? 'bg-violet-100 text-violet-700'
      : 'bg-amber-100 text-amber-700'

  const metricCards = section === 'timeline'
    ? [
        { label: '总资产', value: String(assets.length) },
        { label: '时间节点', value: String(computed.timeline.length) },
        { label: '多媒体', value: String(computed.media.length) },
        { label: '总容量', value: formatBytes(computed.totalBytes) }
      ]
    : section === 'archives'
      ? [
          { label: '档案文档', value: String(computed.docs.length) },
          { label: 'PDF/PPT/Word', value: String(computed.docs.filter(item => ['pdf', 'ppt', 'pptx', 'doc', 'docx'].includes(getExtension(item.fileName))).length) },
          { label: '最近更新', value: assets[0]?.uploadTime || '--' },
          { label: '总容量', value: formatBytes(computed.totalBytes) }
        ]
      : [
          { label: '图片', value: String(computed.images.length) },
          { label: '视频', value: String(computed.videos.length) },
          { label: '音频', value: String(computed.audios.length) },
          { label: '多媒体总量', value: String(computed.media.length) }
        ]

  const listForSection = section === 'timeline'
    ? computed.timeline.map(item => ({
        tag: item.period,
        title: item.title,
        desc: `${item.dateText} · ${item.desc}`,
        objectName: item.objectName
      }))
    : section === 'archives'
      ? computed.docs.slice(0, 10).map(item => ({
          tag: getAssetTypeTag(getExtension(item.fileName)),
          title: item.fileName,
          desc: `${item.uploadTime || '未标注时间'} · ${item.size || '未知大小'}`,
          objectName: item.objectName
        }))
      : computed.media.slice(0, 10).map(item => ({
          tag: getAssetTypeTag(getExtension(item.fileName)),
          title: item.fileName,
          desc: `${item.uploadTime || '未标注时间'} · ${item.size || '未知大小'}`,
          objectName: item.objectName
        }))

  const openAssetPreview = (item: HistoryAsset) => {
    const ext = getExtension(item.fileName)
    onOpenPreview({
      fileName: item.fileName,
      objectName: item.objectName,
      url: `/api/asset/view?objectName=${encodeURIComponent(item.objectName)}`,
      sourceType: IMAGE_EXT.has(ext) || VIDEO_EXT.has(ext) || AUDIO_EXT.has(ext) ? 'multimedia' : 'official',
      isPrivate: false
    })
  }

  return (
    <div className="px-5 py-5 md:px-7 md:py-6 space-y-5">
      <div className={`rounded-3xl border bg-gradient-to-br ${accentClass} p-6`}>
        <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_420px] gap-5 items-start">
          <div>
            <h2 className="text-3xl md:text-4xl font-black text-slate-900">{title}</h2>
            <p className="mt-2 text-slate-600 font-semibold">
              基于实时资产数据自动生成内容看板，减少空白区并增强模块可用性。
            </p>
            <div className="mt-5 flex flex-wrap gap-3">
              <Link to={primaryPath} className="px-5 py-2.5 rounded-xl bg-slate-900 text-white font-black hover:bg-slate-800 transition-colors">
                {primaryText}
              </Link>
              <Link to="/" className="px-5 py-2.5 rounded-xl bg-white border border-slate-200 text-slate-700 font-bold hover:bg-slate-50 transition-colors">
                返回智能问答
              </Link>
            </div>
          </div>
          <div className="rounded-2xl border border-white/70 bg-white/75 backdrop-blur p-4 space-y-3">
            <p className="text-xs font-black tracking-[0.2em] uppercase text-slate-400">最近入库</p>
            {computed.latestThree.length > 0 ? computed.latestThree.map(item => (
              <div key={item.objectName} className="flex items-center justify-between gap-3 rounded-xl bg-white px-3 py-2 border border-slate-100">
                <p className="min-w-0 truncate text-sm font-bold text-slate-800">{item.fileName}</p>
                <span className={`shrink-0 px-2 py-1 rounded-lg text-xs font-black ${badgeClass}`}>
                  {getAssetTypeTag(getExtension(item.fileName))}
                </span>
              </div>
            )) : (
              <div className="text-sm font-semibold text-slate-500">暂无资产，请先上传校史资料。</div>
            )}
          </div>
        </div>
      </div>

      {loading ? (
        <div className="rounded-3xl border border-slate-100 bg-white p-10 flex items-center gap-3 text-slate-500 font-bold">
          <Loader2 size={20} className="animate-spin" />
          正在加载真实数据...
        </div>
      ) : error ? (
        <div className="rounded-3xl border border-rose-100 bg-rose-50 p-8 text-rose-600 font-bold">
          {error}
        </div>
      ) : (
        <>
          <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
            {metricCards.map(metric => (
              <div key={metric.label} className="rounded-2xl border border-slate-100 bg-white p-4 shadow-sm">
                <p className="text-xs font-black uppercase tracking-widest text-slate-400">{metric.label}</p>
                <p className="mt-2 text-2xl font-black text-slate-900 break-all">{metric.value}</p>
              </div>
            ))}
          </div>

          <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)] gap-4">
            <div className="rounded-3xl border border-slate-100 bg-white p-4">
              <div className="flex items-center justify-between">
                <h3 className="text-xl font-black text-slate-900">
                  {section === 'timeline' ? '时间脉络' : section === 'archives' ? '档案明细' : '多媒体明细'}
                </h3>
                <span className={`px-2.5 py-1 rounded-lg text-xs font-black ${badgeClass}`}>
                  TOP {Math.min(10, listForSection.length)}
                </span>
              </div>
              <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-3">
                {listForSection.length > 0 ? listForSection.map(item => (
                  <button
                    key={`${item.tag}-${item.title}-${item.objectName}`}
                    type="button"
                    onClick={() => {
                      const matched = assets.find(asset => asset.objectName === item.objectName)
                      if (matched) openAssetPreview(matched)
                    }}
                    className="w-full text-left rounded-2xl border border-slate-100 p-4 hover:border-slate-200 hover:bg-slate-50 transition-colors"
                  >
                    <p className="text-xs font-black uppercase tracking-[0.18em] text-slate-400">{item.tag}</p>
                    <p className="mt-2 text-base font-black text-slate-900 line-clamp-1">{item.title}</p>
                    <p className="mt-1 text-sm font-semibold text-slate-500 line-clamp-2">{item.desc}</p>
                  </button>
                )) : (
                  <div className="text-sm font-semibold text-slate-500">暂无可展示条目</div>
                )}
              </div>
            </div>

            <div className="rounded-3xl border border-slate-100 bg-white p-4 space-y-4">
              <h3 className="text-xl font-black text-slate-900">快速访问</h3>
              {assets.slice(0, 8).map(item => (
                <button
                  key={item.objectName}
                  type="button"
                  onClick={() => openAssetPreview(item)}
                  className="w-full text-left flex items-center justify-between rounded-xl border border-slate-100 px-3 py-2.5 hover:border-slate-300 hover:bg-slate-50 transition-colors"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-bold text-slate-800">{item.fileName}</p>
                    <p className="text-xs font-semibold text-slate-500">{item.uploadTime || '--'} · {item.size || '--'}</p>
                  </div>
                  <ChevronRight size={16} className="text-slate-400 shrink-0" />
                </button>
              ))}
              {assets.length === 0 && (
                <div className="text-sm font-semibold text-slate-500">还没有资产数据，先上传文件后即可自动生成真实看板。</div>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function AuthModal({
  open,
  onClose,
  onSuccess
}: {
  open: boolean
  onClose: () => void
  onSuccess: (user: AuthUser) => void
}) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [registerRole, setRegisterRole] = useState<'student' | 'teacher'>('student')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!open) {
      setMode('login')
      setUsername('')
      setPassword('')
      setRegisterRole('student')
      setError('')
    }
  }, [open])

  if (!open) return null

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!username.trim() || !password.trim()) return
    setLoading(true)
    setError('')
    try {
      const payload = {
        username: username.trim(),
        password: password.trim(),
        ...(mode === 'register' ? { role: registerRole } : {})
      }
      const response = await axios.post(mode === 'login' ? '/api/auth/login' : '/api/auth/register', payload)
      if (!response.data?.success) {
        setError(response.data?.message || '操作失败，请稍后重试')
        return
      }
      const role = (response.data.role || 'student') as UserRole
      const userId = String(response.data.userId || response.data.username || '')
      const displayName = String(response.data.displayName || response.data.username || '')
      if (!userId || !displayName) {
        setError('登录信息不完整，请联系管理员')
        return
      }
      onSuccess({ userId, username: displayName, role })
      onClose()
    } catch {
      setError('服务器连接失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 z-[150] flex items-center justify-center bg-slate-900/45 backdrop-blur-md p-4 md:p-8">
      <div className="w-full max-w-4xl overflow-hidden rounded-[2rem] border border-white/30 bg-white shadow-[0_28px_80px_-24px_rgba(15,23,42,0.7)]">
        <div className="grid md:grid-cols-[1.08fr_1fr]">
          <div className="hidden md:flex flex-col justify-between bg-gradient-to-br from-slate-900 via-blue-900 to-indigo-700 p-8 lg:p-10 text-white">
            <div>
              <div className="inline-flex items-center gap-2 rounded-full bg-white/15 px-3 py-1.5 text-xs font-black tracking-wider">
                <Sparkles size={14} />
                校园智能体平台
              </div>
              <h3 className="mt-5 text-4xl font-black leading-tight">欢迎回来</h3>
              <p className="mt-4 text-sm font-semibold text-blue-100/90 leading-relaxed">
                统一登录后可访问成绩分析、学业中心、私人空间与资料问答。
              </p>
            </div>
            <div className="space-y-3">
              <div className="rounded-2xl bg-white/10 p-4">
                <p className="text-xs font-bold text-blue-100/90">支持角色</p>
                <p className="mt-1 text-base font-black">学生 / 教师 / 管理员</p>
              </div>
              <div className="rounded-2xl bg-white/10 p-4">
                <p className="text-xs font-bold text-blue-100/90">数据安全</p>
                <p className="mt-1 text-base font-black">角色隔离 · 私有空间保护</p>
              </div>
            </div>
          </div>
          <div className="p-6 sm:p-8 lg:p-10">
            <div className="flex items-start justify-between">
              <div>
                <h3 className="text-3xl font-black text-slate-900">{mode === 'login' ? '账号登录' : '创建账号'}</h3>
                <p className="mt-1 text-sm font-semibold text-slate-500">
                  {mode === 'login' ? '进入校园记忆引擎控制台' : '完成注册后立即体验智能问答'}
                </p>
              </div>
              <button onClick={onClose} className="rounded-xl px-3 py-2 text-sm font-bold text-slate-500 hover:bg-slate-100">关闭</button>
            </div>
            <div className="mt-6 grid grid-cols-2 gap-2 rounded-2xl bg-slate-100 p-1.5">
              <button
                onClick={() => { setMode('login'); setError('') }}
                className={cn("h-11 rounded-xl text-sm font-black transition", mode === 'login' ? "bg-slate-900 text-white shadow" : "text-slate-500")}
              >
                登录
              </button>
              <button
                onClick={() => { setMode('register'); setError('') }}
                className={cn("h-11 rounded-xl text-sm font-black transition", mode === 'register' ? "bg-slate-900 text-white shadow" : "text-slate-500")}
              >
                注册
              </button>
            </div>
            <form onSubmit={handleSubmit} className="mt-7 space-y-5">
              <div className="space-y-2">
                <p className="text-sm font-black text-slate-700">账号</p>
                <input
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder={mode === 'register' && registerRole === 'student' ? '请输入学号' : '请输入账号'}
                  className="w-full h-14 px-4 rounded-2xl border border-slate-200 bg-white text-base font-semibold text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
              <div className="space-y-2">
                <p className="text-sm font-black text-slate-700">密码</p>
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="请输入密码"
                  className="w-full h-14 px-4 rounded-2xl border border-slate-200 bg-white text-base font-semibold text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
              {mode === 'register' && (
                <div className="space-y-2">
                  <p className="text-sm font-black text-slate-700">注册角色</p>
                  <select
                    value={registerRole}
                    onChange={(e) => setRegisterRole(e.target.value as 'student' | 'teacher')}
                    className="w-full h-14 px-4 rounded-2xl border border-slate-200 bg-white text-base font-semibold text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  >
                    <option value="student">学生（账号=学号）</option>
                    <option value="teacher">教师</option>
                  </select>
                </div>
              )}
              {error && <p className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-bold text-rose-600">{error}</p>}
              <button
                disabled={loading}
                className="w-full h-14 rounded-2xl bg-gradient-to-r from-slate-900 to-blue-800 text-white text-base font-black tracking-wide hover:from-slate-800 hover:to-blue-700 disabled:opacity-60"
              >
                {loading ? '处理中...' : mode === 'login' ? '立即登录' : '完成注册'}
              </button>
            </form>
            <div className="mt-4 flex items-center gap-2 text-xs font-bold text-slate-400">
              <Lock size={14} />
              系统采用角色权限隔离，保障账号与数据安全
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function ScoreAnalyticsPage({
  authHeaders,
  user
}: {
  authHeaders: Record<string, string>
  user: AuthUser
}) {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [statistics, setStatistics] = useState<ScoreStatisticsResponse | null>(null)
  const [insight, setInsight] = useState<StudentInsightResponse | null>(null)
  const [searchKeyword, setSearchKeyword] = useState('')
  const [searching, setSearching] = useState(false)
  const isStudent = user.role === 'student'
  const isTeacherOrAdmin = user.role === 'teacher' || user.role === 'admin'

  useEffect(() => {
    let mounted = true

    const loadStatistics = async () => {
      try {
        setLoading(true)
        setError('')
        const response = await axios.get<ScoreStatisticsResponse>('/api/score/statistics', {
          headers: authHeaders
        })
        if (!mounted) return
        if (response.data?.error) {
          setError(response.data.error)
          return
        }
        setStatistics(response.data)
        if (isStudent) {
          const insightResponse = await axios.get<StudentInsightResponse>('/api/score/student-insights', {
            headers: authHeaders
          })
          if (!mounted) return
          if (insightResponse.data?.error) {
            setError(insightResponse.data.error)
            return
          }
          setInsight(insightResponse.data)
        } else {
          setInsight(null)
        }
      } catch {
        if (!mounted) return
        setError('成绩统计接口请求失败，请检查后端服务与数据库连接。')
      } finally {
        if (mounted) setLoading(false)
      }
    }

    loadStatistics()
    return () => {
      mounted = false
    }
  }, [authHeaders, isStudent])

  const loadTargetStudentInsight = async () => {
    const keyword = searchKeyword.trim()
    if (!keyword) {
      setError('请输入学号或姓名后再查询。')
      return
    }
    try {
      setSearching(true)
      setError('')
      const response = await axios.get<StudentInsightResponse>('/api/score/student-insights', {
        headers: authHeaders,
        params: { identifier: keyword }
      })
      if (response.data?.error) {
        setError(response.data.error)
        return
      }
      setInsight(response.data)
    } catch {
      setError('学生成绩查询失败，请检查后端服务与数据库连接。')
    } finally {
      setSearching(false)
    }
  }

  const resetInsightView = () => {
    setInsight(null)
    setError('')
    setSearchKeyword('')
  }

  const lineConfig: ChartConfig = (insight?.trendChartConfig ?? statistics?.lineChartConfig) ?? {
    type: 'line',
    title: insight ? '个人学期平均分趋势' : '当前平均分',
    labels: ['实时'],
    datasets: [{ label: '平均分', data: [0] }]
  }
  const pieConfig: ChartConfig = (insight?.overview?.pieChartConfig ?? statistics?.pieChartConfig) ?? {
    type: 'pie',
    title: '成绩等级分布',
    labels: ['优秀', '良好', '中等', '及格'],
    datasets: [{ label: '占比', data: [0, 0, 0, 0] }]
  }
  const radarConfig: ChartConfig = insight?.radarChartConfig ?? {
    type: 'radar',
    title: '个人能力雷达图',
    labels: ['数理逻辑', '工程实践', '人文感悟', '持续耐力', '极限爆发'],
    datasets: [{ label: '能力评分', data: [0, 0, 0, 0, 0] }]
  }
  const weakCourseConfig: ChartConfig = insight?.weakCourseChartConfig ?? {
    type: 'bar',
    title: '待提升课程',
    labels: ['暂无数据'],
    datasets: [{ label: '课程分数', data: [0] }]
  }
  const studentStats = insight?.overview ?? statistics
  const showInsight = Boolean(insight)

  return (
    <div className="px-8 py-8 md:px-12 md:py-10 space-y-6">
      <div className="rounded-3xl border border-emerald-100 bg-gradient-to-br from-emerald-50 to-cyan-50 p-8">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <GraduationCap className="text-emerald-600" size={28} />
            <h2 className="text-4xl font-black text-slate-900">成绩分析中心</h2>
          </div>
          {showInsight && (
            <div className="rounded-2xl border border-emerald-200 bg-white/90 px-4 py-3">
              <p className="text-xs font-black uppercase tracking-wider text-emerald-600">{isStudent ? '学生画像' : '查询结果'}</p>
              <p className="mt-1 text-lg font-black text-slate-900">{insight?.studentName || user.username}</p>
              <p className="text-xs font-bold text-slate-500">当前重点：{insight?.primaryFocus || '基础能力'}</p>
            </div>
          )}
        </div>
        {isTeacherOrAdmin && (
          <div className="mt-5 grid grid-cols-1 md:grid-cols-[1fr_auto_auto] gap-3">
            <input
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  void loadTargetStudentInsight()
                }
              }}
              placeholder="输入学号或姓名，查询单个学生成绩洞察"
              className="h-12 rounded-2xl border border-emerald-200 bg-white px-4 text-sm font-semibold text-slate-800 focus:outline-none focus:ring-2 focus:ring-emerald-500"
            />
            <button
              onClick={() => { void loadTargetStudentInsight() }}
              disabled={searching}
              className="h-12 px-6 rounded-2xl bg-emerald-600 text-white text-sm font-black hover:bg-emerald-500 disabled:opacity-60"
            >
              {searching ? '查询中...' : '查询学生'}
            </button>
            <button
              onClick={resetInsightView}
              disabled={searching || !showInsight}
              className="h-12 px-6 rounded-2xl border border-slate-200 bg-white text-sm font-black text-slate-700 hover:bg-slate-50 disabled:opacity-50"
            >
              回退
            </button>
          </div>
        )}
        {loading && (
          <p className="mt-4 text-sm font-bold text-emerald-700">正在从数据库加载最新统计...</p>
        )}
        {error && (
          <p className="mt-4 text-sm font-bold text-red-600">{error}</p>
        )}
      </div>
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
        <ChartCard config={lineConfig} className="rounded-2xl border border-slate-100 p-4 shadow-sm" />
        <ChartCard config={pieConfig} className="rounded-2xl border border-slate-100 p-4 shadow-sm" />
      </div>
      {showInsight && (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
          <ChartCard config={radarConfig} className="rounded-2xl border border-slate-100 p-4 shadow-sm" />
          <ChartCard config={weakCourseConfig} className="rounded-2xl border border-slate-100 p-4 shadow-sm" />
        </div>
      )}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          [showInsight ? '个人样本' : '学生总数', String(studentStats?.totalStudents ?? 0)],
          [showInsight ? '成绩记录' : '样本总量', String(studentStats?.totalScoreRecords ?? 0)],
          ['优秀率', `${studentStats?.excellentRate ?? 0}%`],
          [showInsight ? '预警科目' : '预警人数', String(studentStats?.warningStudents ?? 0)]
        ].map(([label, value]) => (
          <div key={label} className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
            <p className="text-xs font-black uppercase tracking-widest text-slate-400">{label}</p>
            <p className="mt-2 text-2xl font-black text-slate-900">{value}</p>
          </div>
        ))}
      </div>
      {showInsight && (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          <div className="rounded-2xl border border-blue-100 bg-gradient-to-br from-blue-50 to-cyan-50 p-6">
            <div className="flex items-center gap-2">
              <Sparkles size={18} className="text-blue-600" />
              <h3 className="text-xl font-black text-slate-900">AI 智能建议</h3>
            </div>
            <div className="mt-4 space-y-3">
              {(insight?.aiSuggestions && insight.aiSuggestions.length > 0 ? insight.aiSuggestions : ['暂无建议，请先完成成绩数据同步。']).map((item, idx) => (
                <div key={`${item}-${idx}`} className="rounded-xl border border-blue-100 bg-white px-4 py-3 text-sm font-semibold text-slate-700">
                  {idx + 1}. {item}
                </div>
              ))}
            </div>
          </div>
          <div className="rounded-2xl border border-slate-100 bg-white p-6">
            <h3 className="text-xl font-black text-slate-900">重点课程追踪</h3>
            <div className="mt-4 space-y-3">
              {(insight?.weakCourses && insight.weakCourses.length > 0 ? insight.weakCourses.slice(0, 4) : []).map((course) => (
                <div key={course.course_name} className="rounded-xl border border-rose-100 bg-rose-50 px-4 py-3">
                  <p className="text-sm font-black text-slate-900">{course.course_name}</p>
                  <p className="mt-1 text-xs font-bold text-rose-700">当前分数：{course.score}</p>
                </div>
              ))}
              {(!insight?.weakCourses || insight.weakCourses.length === 0) && (
                <div className="rounded-xl border border-slate-100 bg-slate-50 px-4 py-3 text-sm font-semibold text-slate-500">
                  暂无课程数据
                </div>
              )}
            </div>
          </div>
        </div>
      )}
      {isTeacherOrAdmin && !showInsight && !loading && (
        <div className="rounded-2xl border border-slate-100 bg-white p-6 text-sm font-semibold text-slate-500">
          请输入学号或姓名，查询对应学生的成绩雷达图与AI建议。
        </div>
      )}
    </div>
  )
}

function MajorShowcasePage({
  user,
  authHeaders
}: {
  user: AuthUser | null
  authHeaders: Record<string, string>
}) {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [statistics, setStatistics] = useState<MajorStatisticsResponse | null>(null)

  useEffect(() => {
    let mounted = true
    const loadStatistics = async () => {
      try {
        setLoading(true)
        setError('')
        const response = await axios.get<MajorStatisticsResponse>('/api/score/major-statistics', {
          headers: authHeaders
        })
        if (!mounted) return
        if (response.data?.error) {
          setError(response.data.error)
          return
        }
        setStatistics(response.data)
      } catch {
        if (!mounted) return
        setError('专业统计接口请求失败，暂时展示默认值。')
      } finally {
        if (mounted) setLoading(false)
      }
    }
    loadStatistics()
    return () => {
      mounted = false
    }
  }, [authHeaders])

  const majorCount = statistics?.uniqueMajorCount ?? 0
  const departmentCount = statistics?.departmentCount ?? 0
  const directionCount = statistics?.directionCount ?? 0
  const majorRows = statistics?.studentRowsWithMajor ?? 0
  const showValue = (value: number, suffix = '') => (loading ? '--' : `${value}${suffix}`)

  return (
    <ShowcasePage
      title={user ? '学校专业模板' : '游客专业介绍'}
      metrics={user
        ? [
            { label: '专业总数', value: showValue(majorCount) },
            { label: '学院覆盖', value: showValue(departmentCount) },
            { label: '培养方向', value: showValue(directionCount) },
            { label: '更新方式', value: '手动同步' }
          ]
        : [
            { label: '开放专业域', value: showValue(majorCount, ' 类') },
            { label: '特色方向', value: showValue(directionCount) },
            { label: '样本专业', value: showValue(majorRows) },
            { label: '游客可用', value: '24/7' }
          ]}
      items={user
        ? [
            { tag: '模板', title: '专业概览模板', desc: '包含培养目标、核心课程、就业方向、升学路径四段式结构。' },
            { tag: '归类', title: '专业资料规则归档（上传时触发）', desc: '上传时按关键词规则归类到“专业”分类。' },
            { tag: '问答', title: '专业检索建议', desc: error || '建议使用“专业名称 + 课程/就业/学位”组合查询，召回更准确。' }
          ]
        : [
            { tag: '专业', title: '专业方向概览', desc: '快速了解各专业培养目标、学习内容与能力要求。' },
            { tag: '课程', title: '课程体系简介', desc: '支持查询核心课程、实践环节、学分结构与学习路径。' },
            { tag: '发展', title: '就业与升学去向', desc: '可查看典型岗位方向、行业场景与继续深造建议。' }
          ]}
      accent="blue"
      primaryPath="/"
      primaryText={user ? '发起专业问答' : '立即了解专业'}
    />
  )
}

function App() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [showAuthModal, setShowAuthModal] = useState(false)
  const [user, setUser] = useState<AuthUser | null>(null)

  const handleLoginSuccess = (userData: AuthUser) => {
    setUser(userData)
    setChatHistory([])
  }

  const handleLogout = () => {
    setUser(null)
    setChatHistory([])
  }

  const [sessionId] = useState(() => {
    let id = localStorage.getItem('cdame_session_id')
    if (!id) {
      id = Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15)
      localStorage.setItem('cdame_session_id', id)
    }
    return id
  })
  const [chatHistory, setChatHistory] = useState<{ 
    type: 'user' | 'ai', 
    content: string | string[] | SearchResponse 
  }[]>([])
  const [isSearching, setIsSearching] = useState(false)
  const [isUploading, setIsUploading] = useState(false)
  const [isVoiceRecognizing, setIsVoiceRecognizing] = useState(false)
  const [isVoiceRecording, setIsVoiceRecording] = useState(false)
  const [isCameraModalOpen, setIsCameraModalOpen] = useState(false)
  const [isCameraStarting, setIsCameraStarting] = useState(false)
  const [isCameraUploading, setIsCameraUploading] = useState(false)
  const [capturedCameraBlob, setCapturedCameraBlob] = useState<Blob | null>(null)
  const [capturedCameraPreviewUrl, setCapturedCameraPreviewUrl] = useState<string | null>(null)
  const [capturedCameraQualityText, setCapturedCameraQualityText] = useState<string | null>(null)
  const [capturedCameraQualityScore, setCapturedCameraQualityScore] = useState<number | null>(null)
  const [voiceLanguage, setVoiceLanguage] = useState('auto')
  const [syncing, setSyncing] = useState(false);
  const [highlightedFileIdx] = useState<{messageIdx: number, fileIdx: number} | null>(null)
  const [previewFile, setPreviewFile] = useState<NonNullable<AppSearchResponse['relevantFiles']>[number] | null>(null);

  const currentRole = user?.role || 'guest';
  const authHeaders: Record<string, string> = user ? {
    'X-User-Id': user.userId,
    'X-User-Role': user.role
  } : {};

  // ... (FormattedAnswer, handleSync, handleAddLink, handleSearch, handleFileUpload methods remain the same)

  // 格式化回答内容，处理 [CHART_DATA] 图表召唤
  const FormattedAnswer = ({ text }: { text?: string }) => {
    if (!text) return null;

    // 1. 提取图表配置
    const chartConfigs: ChartConfig[] = [];
    let cleanText = '';
    let cursor = 0;
    const marker = '[CHART_DATA:';

    while (cursor < text.length) {
      const markerIndex = text.indexOf(marker, cursor);
      if (markerIndex === -1) {
        cleanText += text.slice(cursor);
        break;
      }

      cleanText += text.slice(cursor, markerIndex);
      const jsonStart = text.indexOf('{', markerIndex + marker.length);
      if (jsonStart === -1) {
        cursor = markerIndex + marker.length;
        continue;
      }

      let depth = 0;
      let inString = false;
      let escaped = false;
      let jsonEnd = -1;

      for (let i = jsonStart; i < text.length; i++) {
        const ch = text[i];

        if (inString) {
          if (escaped) {
            escaped = false;
          } else if (ch === '\\') {
            escaped = true;
          } else if (ch === '"') {
            inString = false;
          }
          continue;
        }

        if (ch === '"') {
          inString = true;
          continue;
        }

        if (ch === '{') depth++;
        if (ch === '}') {
          depth--;
          if (depth === 0) {
            jsonEnd = i;
            break;
          }
        }
      }

      if (jsonEnd === -1) {
        cursor = markerIndex + marker.length;
        continue;
      }

      let endIndex = jsonEnd + 1;
      while (endIndex < text.length && /\s/.test(text[endIndex])) endIndex++;
      if (text[endIndex] === ']') {
        const jsonStr = text.slice(jsonStart, jsonEnd + 1).trim();
        try {
          chartConfigs.push(JSON.parse(jsonStr));
        } catch (e) {
          console.error('Failed to parse chart data:', e);
        }
        cursor = endIndex + 1;
      } else {
        cursor = jsonEnd + 1;
      }
    }

    // 2. 清理正文中的无效链接
    cleanText = cleanText
      .replace(/\[.*📄.*\]\(.*\/api\/asset\/download\/.*\)/g, '')
      .replace(/\[.*📄.*\]\(.*\/api\/asset\/view\?.*\)/g, '')
      .replace(/\[([^\]]+)\]\(\)/g, '$1')
      .replace(/\[\d+\]/g, '')
      .replace(/\/api\/asset\/download\/[^\s)]+/g, '')
      .replace(/\/api\/asset\/view\?[^\s)]+/g, '')
      .replace(/随时间下载查阅原始文件：/g, '')
      .replace(/\n*[-•]?\s*文件名[:：][^\n]*分类[:：][^\n]*内容[:：][\s\S]*$/g, '')
      .replace(/^\s*[-•]\s*$/gm, '')
      .replace(/^\s*\d+\s*$/gm, '')
      .replace(/\n{3,}/g, '\n\n')
      .trim();

    if (!cleanText && chartConfigs.length === 0) return null;

    return (
      <div className="space-y-4">
        {cleanText && (
          <div className="space-y-2 break-words">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                p: ({ children }) => <p className="text-xl text-slate-800 leading-relaxed font-bold">{children}</p>,
                h1: ({ children }) => <h1 className="text-2xl text-slate-900 font-black mt-2 mb-1">{children}</h1>,
                h2: ({ children }) => <h2 className="text-xl text-slate-900 font-black mt-2 mb-1">{children}</h2>,
                h3: ({ children }) => <h3 className="text-lg text-slate-900 font-black mt-2 mb-1">{children}</h3>,
                ul: ({ children }) => <ul className="list-disc pl-6 text-xl text-slate-800 font-bold">{children}</ul>,
                ol: ({ children }) => <ol className="list-decimal pl-6 text-xl text-slate-800 font-bold">{children}</ol>,
                li: ({ children }) => <li className="mb-1">{children}</li>,
                table: ({ children }) => (
                  <div className="overflow-x-auto my-4 rounded-2xl border border-blue-100 bg-white">
                    <table className="min-w-full text-base">{children}</table>
                  </div>
                ),
                thead: ({ children }) => <thead className="bg-blue-50">{children}</thead>,
                th: ({ children }) => <th className="px-4 py-3 text-left text-slate-800 font-black border-b border-blue-100">{children}</th>,
                td: ({ children }) => <td className="px-4 py-3 text-slate-700 font-semibold border-b border-slate-100">{children}</td>
              }}
            >
              {cleanText}
            </ReactMarkdown>
          </div>
        )}
        
        {/* 渲染图表 */}
        {chartConfigs.map((config, idx) => (
          <ChartCard key={idx} config={config} />
        ))}
      </div>
    );
  };

  const handleSync = async () => {
    if (!confirm('确定要同步所有 MinIO 文档到向量库吗？这可能需要一些时间。')) return;
    setSyncing(true);
    try {
      const response = await fetch('/api/asset/sync', {
        method: 'POST',
        headers: authHeaders
      });
      const msg = await response.text();
      alert(msg);
    } catch (error) {
      console.error('同步失败:', error);
      alert('同步请求失败');
    } finally {
      setSyncing(false);
    }
  };
  const handleAddLink = async () => {
    const title = prompt('请输入链接标题:');
    if (!title) return;
    const url = prompt('请输入链接 URL:');
    if (!url) return;
    const description = prompt('请输入链接描述 (可选):') || '';
    
    setIsUploading(true);
    try {
      await axios.post('/api/asset/link', null, {
        headers: authHeaders,
        params: { 
          title, 
          url, 
          description,
          role: currentRole === 'admin' ? 'teacher' : currentRole
        }
      });
      setUploadStatus({ type: 'success', message: `链接 "${title}" 已成功保存并向量化！` });
    } catch (error) {
      setUploadStatus({ type: 'error', message: `链接 "${title}" 保存失败。` });
      console.error('Link save failed:', error);
    } finally {
      setIsUploading(false);
    }
  };

  const [uploadStatus, setUploadStatus] = useState<{ type: 'success' | 'error', message: string } | null>(null)
  const chatEndRef = useRef<HTMLDivElement>(null)
  const mediaRecorderRef = useRef<MediaRecorder | null>(null)
  const audioStreamRef = useRef<MediaStream | null>(null)
  const voiceChunksRef = useRef<Blob[]>([])
  const cameraVideoRef = useRef<HTMLVideoElement | null>(null)
  const cameraCanvasRef = useRef<HTMLCanvasElement | null>(null)
  const cameraStreamRef = useRef<MediaStream | null>(null)
  const cameraUploadAbortRef = useRef<AbortController | null>(null)

  // 自动滚动到底部
  const scrollToBottom = () => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }

  useEffect(() => {
    scrollToBottom()
  }, [chatHistory, isSearching])

  useEffect(() => {
    return () => {
      if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
        mediaRecorderRef.current.stop()
      }
      if (audioStreamRef.current) {
        audioStreamRef.current.getTracks().forEach(track => track.stop())
      }
      if (cameraStreamRef.current) {
        cameraStreamRef.current.getTracks().forEach(track => track.stop())
        cameraStreamRef.current = null
      }
      if (cameraUploadAbortRef.current) {
        cameraUploadAbortRef.current.abort()
        cameraUploadAbortRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    if (!uploadStatus) {
      return
    }
    const timer = window.setTimeout(() => {
      setUploadStatus(null)
    }, 5000)
    return () => {
      window.clearTimeout(timer)
    }
  }, [uploadStatus])

  const handleSearch = async (e?: React.FormEvent, customQuery?: string) => {
    if (e) e.preventDefault()
    const targetQuery = customQuery || query
    if (!targetQuery.trim()) return

    setQuery('')
    setChatHistory(prev => [...prev, { type: 'user', content: targetQuery }])
    setIsSearching(true)
    
    try {
      const response = await axios.post('/api/memory/search', targetQuery, {
        headers: { 
          ...authHeaders,
          'Content-Type': 'text/plain; charset=utf-8',
          'X-Session-Id': user?.userId || sessionId
        },
        params: { 
          maxResults: 5,
          role: currentRole // 传入当前选定的角色进行检索隔离
        }
      })
      // 后端现在返回 SearchResponse 对象
      setChatHistory(prev => [...prev, { type: 'ai', content: response.data }])
    } catch (error: unknown) {
      console.error('Search failed:', error)
      const axiosError = error as { response?: { data?: { message?: string } }; message?: string }
      const errorMessage = axiosError.response?.data?.message || axiosError.message || '未知错误'
      setChatHistory(prev => [...prev, { type: 'ai', content: { 
        answer: `抱歉，搜索过程中发生了错误：${errorMessage}。请检查后端服务是否正常运行。`, 
        memories: [], 
        needsClarification: false, 
        clarificationSuggestions: [] 
      } as SearchResponse }])
    } finally {
      setIsSearching(false)
    }
  }

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || [])
    if (files.length === 0) return

    setIsUploading(true)
    setUploadStatus(null)
    const uploadRole = 'all'

    try {
      const successFiles: string[] = []
      const failedFiles: string[] = []

      for (const file of files) {
        const formData = new FormData()
        formData.append('file', file)
        try {
          await axios.post('/api/asset/upload', formData, {
            headers: {
              ...authHeaders,
              'Content-Type': 'multipart/form-data'
            },
            params: { role: uploadRole }
          })
          successFiles.push(file.name)
        } catch (error) {
          failedFiles.push(file.name)
          console.error('Upload failed:', error)
        }
      }

      if (failedFiles.length === 0) {
        setUploadStatus({ type: 'success', message: `批量上传完成：共 ${successFiles.length} 个文件已上传至公共校史库。` })
      } else if (successFiles.length === 0) {
        setUploadStatus({ type: 'error', message: `批量上传失败：共 ${failedFiles.length} 个文件上传失败。` })
      } else {
        setUploadStatus({
          type: 'error',
          message: `批量上传部分成功：成功 ${successFiles.length} 个，失败 ${failedFiles.length} 个（失败：${failedFiles.slice(0, 3).join('、')}${failedFiles.length > 3 ? ' 等' : ''}）。`
        })
      }
    } catch (error) {
      setUploadStatus({ type: 'error', message: '批量上传失败，请稍后重试。' })
      console.error('Upload failed:', error)
    } finally {
      setIsUploading(false)
      e.target.value = ''
    }
  }

  const submitVoiceBlob = async (voiceBlob: Blob) => {
    setIsVoiceRecognizing(true)
    setUploadStatus(null)

    try {
      const file = new File([voiceBlob], `recording-${Date.now()}.webm`, {
        type: voiceBlob.type || 'audio/webm'
      })
      const formData = new FormData()
      formData.append('file', file)
      const response = await axios.post<VoiceInputResponse>('/api/asset/multimodal/voice/transcribe', formData, {
        headers: {
          ...authHeaders,
          'Content-Type': 'multipart/form-data'
        },
        params: {
          language: voiceLanguage === 'auto' ? undefined : voiceLanguage
        }
      })

      const recognizedText = response.data?.recognizedText?.trim() || ''
      const recognizedLanguage = response.data?.recognizedLanguage || 'unknown'

      if (recognizedText) {
        setQuery(recognizedText)
      }

      setUploadStatus({
        type: 'success',
        message: recognizedText
          ? `Whisper 识别成功（语言: ${recognizedLanguage}），已填入输入框。`
          : `Whisper 识别成功（语言: ${recognizedLanguage}），但未识别到有效文本。`
      })
    } catch (error: unknown) {
      const axiosError = error as { response?: { data?: { message?: string } }; message?: string }
      const errorMessage = axiosError.response?.data?.message || axiosError.message || '未知错误'
      setUploadStatus({ type: 'error', message: `Whisper 识别失败：${errorMessage}` })
    } finally {
      setIsVoiceRecognizing(false)
    }
  }

  const startVoiceRecording = async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setUploadStatus({ type: 'error', message: '当前浏览器不支持录音功能。' })
      return
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      audioStreamRef.current = stream
      voiceChunksRef.current = []

      const preferredMimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : undefined
      const recorder = preferredMimeType
        ? new MediaRecorder(stream, { mimeType: preferredMimeType })
        : new MediaRecorder(stream)
      mediaRecorderRef.current = recorder

      recorder.ondataavailable = (event: BlobEvent) => {
        if (event.data.size > 0) {
          voiceChunksRef.current.push(event.data)
        }
      }
      recorder.onstop = async () => {
        const blob = new Blob(voiceChunksRef.current, { type: recorder.mimeType || 'audio/webm' })
        voiceChunksRef.current = []
        if (audioStreamRef.current) {
          audioStreamRef.current.getTracks().forEach(track => track.stop())
          audioStreamRef.current = null
        }
        mediaRecorderRef.current = null
        if (blob.size > 0) {
          await submitVoiceBlob(blob)
        } else {
          setUploadStatus({ type: 'error', message: '录音内容为空，请重试。' })
        }
      }
      recorder.start()
      setIsVoiceRecording(true)
      setUploadStatus({ type: 'success', message: '录音中，再次点击语音按钮结束录音并转写。' })
    } catch (error) {
      setIsVoiceRecording(false)
      const message = error instanceof Error ? error.message : '无法启用麦克风'
      setUploadStatus({ type: 'error', message: `录音启动失败：${message}` })
    }
  }

  const stopVoiceRecording = () => {
    const recorder = mediaRecorderRef.current
    if (recorder && recorder.state !== 'inactive') {
      recorder.stop()
    }
    setIsVoiceRecording(false)
    setUploadStatus({ type: 'success', message: '录音结束，正在识别并填入输入框...' })
  }

  const handleVoiceToggle = async () => {
    if (isVoiceRecognizing) return
    if (isVoiceRecording) {
      stopVoiceRecording()
      return
    }
    await startVoiceRecording()
  }

  const stopCameraStream = () => {
    if (cameraStreamRef.current) {
      cameraStreamRef.current.getTracks().forEach(track => track.stop())
      cameraStreamRef.current = null
    }
    if (cameraVideoRef.current) {
      cameraVideoRef.current.srcObject = null
    }
  }

  const isUploadCanceledError = (error: unknown) => {
    const axiosError = error as { code?: string; name?: string }
    return axios.isCancel(error) || axiosError?.code === 'ERR_CANCELED' || axiosError?.name === 'CanceledError'
  }

  const cancelCameraUpload = () => {
    if (cameraUploadAbortRef.current) {
      cameraUploadAbortRef.current.abort()
      cameraUploadAbortRef.current = null
    }
  }

  const handleCameraModalCancel = () => {
    const wasUploading = isCameraUploading
    cancelCameraUpload()
    clearCapturedCameraPreview()
    setIsCameraModalOpen(false)
    if (wasUploading) {
      setUploadStatus({ type: 'error', message: '已取消当前图片上传。' })
    }
  }

  const clearCapturedCameraPreview = () => {
    setCapturedCameraBlob(null)
    setCapturedCameraQualityText(null)
    setCapturedCameraQualityScore(null)
    setCapturedCameraPreviewUrl((prev) => {
      if (prev) {
        URL.revokeObjectURL(prev)
      }
      return null
    })
  }

  useEffect(() => {
    if (!isCameraModalOpen) {
      clearCapturedCameraPreview()
      stopCameraStream()
      return
    }

    let cancelled = false

    const startCameraPreview = async () => {
      if (!navigator.mediaDevices?.getUserMedia) {
        setUploadStatus({ type: 'error', message: '当前浏览器不支持摄像头功能。' })
        setIsCameraModalOpen(false)
        return
      }

      setIsCameraStarting(true)
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: {
            facingMode: 'environment',
            width: { ideal: 2560 },
            height: { ideal: 1440 }
          },
          audio: false
        })
        if (cancelled) {
          stream.getTracks().forEach(track => track.stop())
          return
        }
        cameraStreamRef.current = stream
        if (cameraVideoRef.current) {
          cameraVideoRef.current.srcObject = stream
          await cameraVideoRef.current.play().catch(() => undefined)
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : '无法访问摄像头'
        setUploadStatus({ type: 'error', message: `摄像头启动失败：${message}` })
        setIsCameraModalOpen(false)
      } finally {
        if (!cancelled) {
          setIsCameraStarting(false)
        }
      }
    }

    startCameraPreview()

    return () => {
      cancelled = true
      stopCameraStream()
    }
  }, [isCameraModalOpen])

  useEffect(() => {
    if (!isCameraModalOpen || capturedCameraPreviewUrl) {
      return
    }
    const stream = cameraStreamRef.current
    const video = cameraVideoRef.current
    if (!stream || !video) {
      return
    }
    if (video.srcObject !== stream) {
      video.srcObject = stream
    }
    video.play().catch(() => undefined)
  }, [capturedCameraPreviewUrl, isCameraModalOpen])

  const handleCameraCapturePreview = async () => {
    if (user?.role !== 'admin') {
      setUploadStatus({ type: 'error', message: '仅管理员可使用摄像头扫描入库功能。' })
      return
    }

    const video = cameraVideoRef.current
    const canvas = cameraCanvasRef.current
    if (!video || !canvas) {
      setUploadStatus({ type: 'error', message: '摄像头未就绪，请稍后重试。' })
      return
    }
    try {
      const quality = evaluateCameraFrameQuality(video, DOCUMENT_GUIDE_RECT)
      const issueText = quality.issues.join('；')
      const qualityText = `质量评分 ${quality.score}/100`
      if (!quality.passed) {
        setCapturedCameraBlob(null)
        setCapturedCameraPreviewUrl((prev) => {
          if (prev) URL.revokeObjectURL(prev)
          return null
        })
        setCapturedCameraQualityText(null)
        setCapturedCameraQualityScore(null)
        setUploadStatus({
          type: 'error',
          message: `${qualityText}，请重拍。${issueText ? `问题：${issueText}` : ''}`
        })
        return
      }
      setCapturedCameraQualityText(`${qualityText}（可上传）`)
      setCapturedCameraQualityScore(quality.score)
    } catch (error) {
      const message = error instanceof Error ? error.message : '质量检测失败'
      setUploadStatus({ type: 'error', message: `拍照质量检测失败：${message}` })
      return
    }

    let blob: Blob
    try {
      blob = await captureEnhancedCameraFrame(video, canvas, {
        guideRect: DOCUMENT_GUIDE_RECT
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : '图像处理失败'
      setUploadStatus({ type: 'error', message: `拍照失败：${message}` })
      return
    }
    const nextPreviewUrl = URL.createObjectURL(blob)
    setCapturedCameraBlob(blob)
    setCapturedCameraPreviewUrl((prev) => {
      if (prev) {
        URL.revokeObjectURL(prev)
      }
      return nextPreviewUrl
    })
  }

  const handleCameraCaptureUpload = async () => {
    if (user?.role !== 'admin') {
      setUploadStatus({ type: 'error', message: '仅管理员可使用摄像头扫描入库功能。' })
      return
    }
    if (!capturedCameraBlob) {
      setUploadStatus({ type: 'error', message: '请先拍照预览，再确认上传。' })
      return
    }

    setIsCameraUploading(true)
    setIsUploading(true)
    setUploadStatus(null)

    try {
      const controller = new AbortController()
      cameraUploadAbortRef.current = controller
      const file = new File([capturedCameraBlob], `camera-scan-${Date.now()}.png`, { type: capturedCameraBlob.type || 'image/png' })
      const formData = new FormData()
      formData.append('file', file)
      const response = await axios.post<{ message?: string }>('/api/asset/physical/scan-callback', formData, {
        headers: {
          ...authHeaders,
          'Content-Type': 'multipart/form-data'
        },
        params: {
          deviceId: 'WEB-CAM-01',
          qualityScore: capturedCameraQualityScore ?? undefined
        },
        signal: controller.signal
      })

      const message = response.data?.message || '摄像头扫描件已成功入库并进入语义检索。'
      setUploadStatus({ type: 'success', message })
      clearCapturedCameraPreview()
      setIsCameraModalOpen(false)
    } catch (error: unknown) {
      if (isUploadCanceledError(error)) {
        setUploadStatus({ type: 'error', message: '已取消当前图片上传。' })
        return
      }
      const axiosError = error as { response?: { data?: { message?: string } }; message?: string }
      const errorMessage = axiosError.response?.data?.message || axiosError.message || '未知错误'
      setUploadStatus({ type: 'error', message: `摄像头扫描上传失败：${errorMessage}` })
    } finally {
      cameraUploadAbortRef.current = null
      setIsCameraUploading(false)
      setIsUploading(false)
    }
  }

  const getDisplayFileName = (fileName?: string, objectName?: string) => {
    const source = objectName || fileName || ''
    if (source.length > 37 && source[36] === '-') {
      const prefix = source.substring(0, 36)
      if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(prefix)) {
        return source.substring(37)
      }
    }
    if (fileName && fileName.trim()) return fileName
    if (objectName && objectName.trim()) return objectName
    return '未知文档'
  }

  const shouldShowRelevantFiles = (response?: SearchResponse) => {
    return !!response?.relevantFiles && response.relevantFiles.length > 0
  }

  const chatView = (
    <div className="min-h-[calc(100vh-8.5rem)] bg-white text-slate-900 font-sans relative">
      <main className="max-w-[1800px] mx-auto w-full px-10 md:px-16 pb-56">
        {chatHistory.length === 0 ? (
          <div className="py-12 grid grid-cols-1 lg:grid-cols-[320px_minmax(0,1fr)] gap-8 items-start">
            <aside className="hidden lg:block sticky top-32 space-y-5">
              <div className="rounded-3xl p-6 bg-gradient-to-br from-slate-900 via-indigo-900 to-blue-700 text-white shadow-2xl">
                <div className="flex items-center justify-between">
                  <p className="text-sm font-black uppercase tracking-widest text-blue-200">Side Mission</p>
                  <Sparkles size={18} />
                </div>
                <p className="mt-3 text-2xl font-black leading-tight">{user ? '校园记忆探索舱' : '校园公开信息服务台'}</p>
                <p className="mt-3 text-sm font-semibold text-blue-100 leading-relaxed">
                  {user
                    ? '从学业、资料和政策三个入口快速切换，形成你的专属探索路径。'
                    : '面向游客开放公共信息问答，支持查询校史介绍、校园概况、招生信息与公开政策。'}
                </p>
                <div className="mt-5 inline-flex items-center gap-2 px-3 py-1.5 rounded-xl bg-white/15 text-xs font-black tracking-wider uppercase">
                  当前身份 · {user ? user.role : '游客'}
                </div>
              </div>
              <div className="bg-white border border-slate-200 rounded-3xl p-4 space-y-3 shadow-sm">
                <Link to="/policies" className="group h-14 px-4 rounded-2xl bg-emerald-50 hover:bg-emerald-100 text-emerald-700 text-base font-black flex items-center justify-between">
                  政策查询
                  <ChevronRight size={18} className="group-hover:translate-x-1 transition-transform" />
                </Link>
                {!user && (
                  <Link to="/majors" className="group h-14 px-4 rounded-2xl bg-blue-50 hover:bg-blue-100 text-blue-700 text-base font-black flex items-center justify-between">
                    专业介绍
                    <ChevronRight size={18} className="group-hover:translate-x-1 transition-transform" />
                  </Link>
                )}
                {user && (
                  <Link to="/history/archives" className="group h-14 px-4 rounded-2xl bg-blue-50 hover:bg-blue-100 text-blue-700 text-base font-black flex items-center justify-between">
                    校史档案
                    <ChevronRight size={18} className="group-hover:translate-x-1 transition-transform" />
                  </Link>
                )}
                {user && (
                  <Link to="/history/media" className="group h-14 px-4 rounded-2xl bg-violet-50 hover:bg-violet-100 text-violet-700 text-base font-black flex items-center justify-between">
                    多媒体资源
                    <ChevronRight size={18} className="group-hover:translate-x-1 transition-transform" />
                  </Link>
                )}
                <Link to="/history/honor-wall" className="group h-14 px-4 rounded-2xl bg-amber-50 hover:bg-amber-100 text-amber-700 text-base font-black flex items-center justify-between">
                  校园荣誉墙
                  <ChevronRight size={18} className="group-hover:translate-x-1 transition-transform" />
                </Link>
                {user && (
                  <Link to="/academic/scores" className="group h-14 px-4 rounded-2xl bg-emerald-50 hover:bg-emerald-100 text-emerald-700 text-base font-black flex items-center justify-between">
                    成绩分析
                    <ChevronRight size={18} className="group-hover:translate-x-1 transition-transform" />
                  </Link>
                )}
                {user && (
                  <Link to="/private" className="group h-14 px-4 rounded-2xl bg-amber-50 hover:bg-amber-100 text-amber-700 text-base font-black flex items-center justify-between">
                    私有资料库
                    <ChevronRight size={18} className="group-hover:translate-x-1 transition-transform" />
                  </Link>
                )}
                {!user && (
                  <button
                    onClick={() => setShowAuthModal(true)}
                    className="h-14 px-4 rounded-2xl bg-slate-100 hover:bg-slate-200 text-slate-700 text-base font-black flex items-center justify-between w-full transition-colors"
                  >
                    登录后解锁校史记忆/学业
                    <ChevronRight size={18} />
                  </button>
                )}
              </div>
              <div className="bg-white border border-slate-200 rounded-3xl p-5 shadow-sm">
                <p className="text-sm font-black text-slate-400 uppercase tracking-widest">灵感提示</p>
                <p className="mt-3 text-base font-bold text-slate-700 leading-relaxed">
                  {user
                    ? '先问“学校发展时间线”，再追问“对应年份的特色专业”，上下文答案会更完整。'
                    : '先问“学校概况或校史信息”，再追问“招生或政策细则”，可更快缩小检索范围。'}
                </p>
              </div>
            </aside>

            <div className="text-center space-y-12">
              <div className="w-32 h-32 bg-blue-50 text-blue-600 rounded-3xl mx-auto flex items-center justify-center shadow-inner">
                <Sparkles size={58} />
              </div>
              <div className="space-y-5">
                <h2 className="text-5xl md:text-6xl font-black text-slate-900 tracking-tight">哈尔滨信息工程学院数字化记忆引擎</h2>
                <p className="text-2xl md:text-3xl font-semibold text-slate-500 max-w-5xl mx-auto leading-relaxed">
                  {user
                    ? '始建于1995年，是经教育部批准设置的全日制普通本科高等学校，是国家示范性软件技术学院、全国高校毕业生就业工作先进单位。'
                    : '游客模式支持检索校园公开信息，包括校史沿革、办学特色、招生资讯与公开政策问答。'}
                </p>
              </div>
              
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6 max-w-5xl mx-auto pt-10">
                {(user
                  ? ['哈尔滨信息工程学院建于哪一年？', '介绍一下国家示范性软件学院', '学院有哪些特色专业？', '就业工作有哪些荣誉？']
                  : ['哈尔滨信息工程学院建于哪一年？', '介绍一下学校的发展历程', '学院有哪些招生相关信息？', '有哪些公开政策可以查询？']
                ).map((tip) => (
                  <button 
                    key={tip}
                    onClick={() => { handleSearch(undefined, tip); }}
                    className="p-8 text-left text-2xl font-bold text-slate-700 bg-slate-50 border-2 border-slate-100 rounded-2xl hover:border-blue-200 hover:bg-white hover:shadow-lg transition-all"
                  >
                    {tip}
                  </button>
                ))}
              </div>
            </div>
          </div>
        ) : (
          <div className="space-y-10 pt-10 flex flex-col">
            {chatHistory.map((message, idx) => {
              const aiContent = message.type === 'ai' ? (message.content as SearchResponse) : undefined
              const showRelevantFiles = shouldShowRelevantFiles(aiContent)
              return (
              <div 
                key={idx} 
                className={cn(
                  "flex flex-col gap-4 w-full",
                  message.type === 'user' ? "items-end" : "items-start"
                )}
              >
                {message.type === 'user' ? (
                  <div className="chat-bubble user-bubble text-xl font-bold leading-normal">
                    {message.content as string}
                  </div>
                ) : (
                  <div className="flex gap-6 w-full max-w-[95%] items-start">
                    <div className="shrink-0 w-12 h-12 rounded-2xl bg-blue-600 text-white flex items-center justify-center shadow-lg">
                      <Database size={24} />
                    </div>
                    <div className="flex-1 space-y-6">
                      {/* AI 综合回答 */}
                      {(message.content as SearchResponse).answer && (
                        <div className="chat-bubble ai-bubble w-full border-2 border-blue-100 bg-blue-50/30">
                          <div className="flex items-center gap-3 mb-4 border-b border-blue-100/50 pb-3">
                            <Sparkles size={16} className="text-blue-600" />
                            <span className="text-xs font-black text-blue-600 uppercase tracking-widest">
                              AI 综合考据
                            </span>
                          </div>
                          <FormattedAnswer 
                            text={aiContent?.answer}
                          />
                        </div>
                      )}

                      {/* 相关文档文件列表 */}
                      {showRelevantFiles && aiContent?.relevantFiles && aiContent.relevantFiles.length > 0 && (
                        <div className="flex flex-wrap gap-4 px-4">
                          {aiContent.relevantFiles.map((file, fIdx) => {
                            const isLink = file.sourceType === 'link';
                            const isMultimedia = file.sourceType === 'multimedia';
                            const isPrivate = file.isPrivate;
                            const isHighlighted = highlightedFileIdx?.messageIdx === idx && highlightedFileIdx?.fileIdx === fIdx;
                            
                            // 确定图标
                            let FileIcon = FileText;
                            if (isLink) FileIcon = LinkIcon;
                            else if (isMultimedia) {
                              const lower = file.fileName?.toLowerCase() || '';
                              if (lower.endsWith('.mp4') || lower.endsWith('.avi') || lower.endsWith('.mov')) FileIcon = Video;
                              else if (lower.endsWith('.mp3') || lower.endsWith('.wav')) FileIcon = Music;
                            } else if (isPrivate) {
                              FileIcon = Lock;
                            }

                            return (
                              <div
                                key={fIdx}
                                onClick={(e) => {
                                  e.preventDefault();
                                  e.stopPropagation();
                                  setPreviewFile(file);
                                }}
                                role="button"
                                tabIndex={0}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter' || e.key === ' ') {
                                    e.preventDefault();
                                    setPreviewFile(file);
                                  }
                                }}
                                className={cn(
                                  "group flex items-center gap-4 p-4 bg-white border-2 rounded-2xl transition-all min-w-[280px] max-w-sm hover:shadow-md cursor-pointer outline-none focus-visible:ring-2 focus-visible:ring-blue-500",
                                  isHighlighted ? "border-blue-600 ring-4 ring-blue-100 shadow-lg scale-105" :
                                  isPrivate ? "border-amber-100 bg-amber-50/50 hover:border-amber-200" :
                                  isLink ? "border-emerald-100 bg-emerald-50/50 hover:border-emerald-200" :
                                  isMultimedia ? "border-purple-100 bg-purple-50/50 hover:border-purple-200" :
                                  "border-slate-100 hover:border-blue-200"
                                )}
                              >
                                <div className={cn(
                                  "w-12 h-12 rounded-xl flex items-center justify-center transition-colors relative",
                                  isPrivate ? "bg-amber-100 text-amber-600 group-hover:bg-amber-600 group-hover:text-white" :
                                  isLink ? "bg-emerald-100 text-emerald-600 group-hover:bg-emerald-600 group-hover:text-white" :
                                  isMultimedia ? "bg-purple-100 text-purple-600 group-hover:bg-purple-600 group-hover:text-white" :
                                  "bg-blue-50 text-blue-600 group-hover:bg-blue-600 group-hover:text-white"
                                )}>
                                  <FileIcon size={24} />
                                  {isPrivate && !isMultimedia && !isLink && (
                                    <div className="absolute -top-1 -right-1 bg-amber-500 text-white p-0.5 rounded-full border border-white">
                                      <Lock size={10} />
                                    </div>
                                  )}
                                </div>
                                <div className="flex-1 overflow-hidden">
                                  <p className={cn(
                                    "text-base font-bold truncate transition-colors",
                                    isPrivate ? "text-amber-900" : 
                                    isLink ? "text-emerald-900" :
                                    isMultimedia ? "text-purple-900" :
                                    "text-slate-700 group-hover:text-blue-600"
                                  )} title={getDisplayFileName(file.fileName, file.objectName)}>
                                    {getDisplayFileName(file.fileName, file.objectName)}
                                    {isPrivate && <span className="ml-2 text-xs opacity-60 text-amber-600 font-black">(私有)</span>}
                                  </p>
                                  <p className="text-xs text-slate-400 font-medium">
                                    {isPrivate && isMultimedia ? '私有视频/音频 · 点击在线播放' :
                                     isPrivate ? '私有文档 · 点击在线查看' : 
                                     isLink ? '外部资源 · 点击访问链接' :
                                     isMultimedia ? '多媒体资产 · 点击在线播放' :
                                     '校史公开文档 · 点击在线查看'}
                                  </p>
                                </div>
                                <ExternalLink size={16} className={cn(
                                  "transition-colors",
                                  isPrivate ? "text-amber-300 group-hover:text-amber-400" : 
                                  isLink ? "text-emerald-300 group-hover:text-emerald-400" :
                                  isMultimedia ? "text-purple-300 group-hover:text-purple-400" :
                                  "text-slate-300 group-hover:text-blue-400"
                                )} />
                              </div>
                            );
                          })}
                        </div>
                      )}

                      {/* 意图澄清建议 */}
                      {aiContent?.needsClarification && (
                        <div className="chat-bubble ai-bubble w-full border-2 border-amber-100 bg-amber-50/30">
                          <div className="flex items-center gap-3 mb-4 border-b border-amber-100/50 pb-3">
                            <span className="text-xs font-black text-amber-600 uppercase tracking-widest">
                              意图澄清
                            </span>
                          </div>
                          <p className="text-lg text-slate-700 mb-4 font-medium">
                            您的问题可能比较模糊，请问您是想了解以下内容吗？
                          </p>
                          <div className="flex flex-wrap gap-3">
                            {aiContent.clarificationSuggestions.map((suggestion, sIdx) => (
                              <button
                                key={sIdx}
                                onClick={() => handleSearch(undefined, suggestion)}
                                className="px-4 py-2 bg-white border border-amber-200 rounded-xl text-amber-700 hover:bg-amber-100 transition-colors font-bold"
                              >
                                {suggestion}
                              </button>
                            ))}
                          </div>
                        </div>
                      )}

                      {/* AI 执行链路追踪 (State Management) */}
                      {aiContent?.trace && (
                        <TraceDisplay trace={aiContent.trace} />
                      )}

                    </div>
                  </div>
                )}
              </div>
            )})}
            
            {isSearching && (
              <div className="flex gap-6 items-center animate-pulse">
                <div className="shrink-0 w-12 h-12 rounded-2xl bg-slate-100 flex items-center justify-center">
                  <Loader2 size={24} className="text-slate-400 animate-spin" />
                </div>
                <div className="space-y-3 flex-1">
                  <div className="h-5 bg-slate-100 rounded-full w-1/2"></div>
                  <div className="h-5 bg-slate-100 rounded-full w-1/3"></div>
                </div>
              </div>
            )}
            
            <div ref={chatEndRef} className="h-64" />
          </div>
        )}
      </main>

      <div className="input-area border-t border-slate-100 bg-white/95 backdrop-blur-md">
        <div className="max-w-5xl mx-auto">
          <form 
            onSubmit={handleSearch}
            className="flex gap-4 items-center bg-white"
          >
            <div className="flex-1 relative">
              <input 
                type="text" 
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={user ? '询问校史相关问题...' : '询问校园公开信息问题...'}
                className="search-input"
              />
            </div>
            <select
              value={voiceLanguage}
              onChange={(e) => setVoiceLanguage(e.target.value)}
              className="h-[64px] px-4 rounded-2xl border border-slate-200 text-base font-bold text-slate-700 bg-white"
            >
              <option value="auto">自动识别</option>
              <option value="zh">中文</option>
              <option value="en">英文</option>
              <option value="ja">日文</option>
            </select>
            <button
              type="button"
              onClick={handleVoiceToggle}
              disabled={isVoiceRecognizing}
              className={cn(
                "h-[64px] px-5 rounded-2xl text-base font-black transition-all flex items-center gap-2 disabled:opacity-60",
                isVoiceRecording ? "bg-rose-50 text-rose-700 hover:bg-rose-100" : "bg-amber-50 text-amber-700 hover:bg-amber-100"
              )}
            >
              {isVoiceRecognizing ? <Loader2 size={22} className="animate-spin" /> : <Mic size={22} />}
              {isVoiceRecording ? '结束录音' : '语音'}
            </button>
            <button 
              type="submit"
              disabled={isSearching || !query.trim()}
              className="btn-primary h-[64px] flex items-center gap-3 px-8 shadow-xl hover:bg-slate-800"
            >
              {isSearching ? <Loader2 size={24} className="animate-spin" /> : (
                <>
                  <span className="text-xl">开始检索</span>
                  <ChevronRight size={24} />
                </>
              )}
            </button>
          </form>
          {uploadStatus && (
            <div className={cn(
              "mt-4 p-4 rounded-xl text-lg font-bold text-center border shadow-sm",
              uploadStatus.type === 'success' ? "bg-emerald-50 text-emerald-700 border-emerald-100" : "bg-rose-50 text-rose-700 border-rose-100"
            )}>
              {uploadStatus.message}
            </div>
          )}
          
          <p className="text-center mt-6 text-xs font-black text-slate-300 uppercase tracking-[0.3em]">
            Campus Digital AI Memory Engine · Powered by Semantic Search
          </p>
        </div>
      </div>
    </div>
  );

  return (
    <Layout
      user={user}
      onLogout={handleLogout}
      onLogin={() => setShowAuthModal(true)}
      onSync={handleSync}
      onUpload={handleFileUpload}
      onAddLink={handleAddLink}
      onCameraScan={() => {
        if (user?.role !== 'admin') {
          setUploadStatus({ type: 'error', message: '仅管理员可使用摄像头扫描入库功能。' })
          return
        }
        setIsCameraModalOpen(true)
      }}
      isSyncing={syncing}
      isUploading={isUploading}
      isCameraScanning={isCameraUploading || isCameraStarting}
    >
      <Routes>
        <Route path="/" element={chatView} />
        <Route
          path="/private"
          element={
            user
              ? (user.role !== 'admin'
                ? <PrivateSpace user={user} onBack={() => navigate('/')} />
                : <PlaceholderPage title="管理员无私人空间" description="管理员账号仅用于系统治理，请使用教师或学生账号访问私人空间。" />)
              : <PlaceholderPage title="请先登录" description="未登录状态仅支持公共库检索，登录后可访问私有资料库。" />
          }
        />
        <Route path="/history/timeline" element={<Navigate to="/history/archives" replace />} />
        <Route
          path="/history/archives"
          element={
            <HistoryHubPage
              section="archives"
              title="档案库"
              accent="violet"
              primaryPath="/history/media"
              primaryText="浏览多媒体"
              authHeaders={authHeaders}
              onOpenPreview={setPreviewFile}
            />
          }
        />
        <Route
          path="/history/media"
          element={
            <HistoryHubPage
              section="media"
              title="多媒体资源"
              accent="amber"
              primaryPath="/"
              primaryText="返回智能问答"
              authHeaders={authHeaders}
              onOpenPreview={setPreviewFile}
            />
          }
        />
        <Route
          path="/history/honor-wall"
          element={
            <CampusHonorTree currentUser={user ? { userId: user.userId, role: user.role } : null} />
          }
        />
        <Route
          path="/academic/scores"
          element={
            user
              ? <ScoreAnalyticsPage authHeaders={authHeaders} user={user} />
              : <PlaceholderPage title="请先登录" description="未登录状态仅支持公共库检索，登录后可查看个人成绩分析。" />
          }
        />
        <Route path="/academic/majors" element={<Navigate to="/majors" replace />} />
        <Route
          path="/policies"
          element={
            <ShowcasePage
              title={user ? '政策查询' : '游客政策查询'}
              metrics={user
                ? [
                    { label: '政策条目', value: '428' },
                    { label: '高频问题', value: '96' },
                    { label: '更新时间', value: '每日' },
                    { label: '命中率', value: '93%' }
                  ]
                : [
                    { label: '开放政策域', value: '4 类' },
                    { label: '常见问题', value: '96' },
                    { label: '检索响应', value: '< 2s' },
                    { label: '游客可用', value: '24/7' }
                  ]}
              items={user
                ? [
                    { tag: '学业', title: '培养与考核', desc: '覆盖培养方案、课程考核、学分规则等条款。' },
                    { tag: '招生', title: '报考与录取', desc: '整合招生政策、专业目录和录取相关说明。' },
                    { tag: '资助', title: '奖助政策', desc: '统一查询奖学金、助学金和资助流程规定。' }
                  ]
                : [
                    { tag: '招生', title: '报考与录取政策', desc: '快速查询报考条件、录取规则、学费与报到要求。' },
                    { tag: '学籍', title: '培养与毕业规则', desc: '覆盖学分、课程考核、毕业条件、重修补修与学籍异动。' },
                    { tag: '资助', title: '奖助与帮扶流程', desc: '支持查询奖助学金、助学贷款、困难认定与办理材料。' }
                  ]}
              accent="emerald"
              primaryPath="/"
              primaryText={user ? '发起政策问答' : '立即咨询政策'}
            />
          }
        />
        <Route
          path="/majors"
          element={<MajorShowcasePage user={user} authHeaders={authHeaders} />}
        />
        <Route path="/academic/policies" element={<Navigate to="/policies" replace />} />
        <Route path="*" element={<PlaceholderPage title="页面未找到" description="该页面暂未开放，请从导航栏选择其他模块。" />} />
      </Routes>

      {previewFile && (
        <div 
          className="fixed inset-0 bg-slate-900/20 backdrop-blur-sm z-[90] animate-in fade-in duration-300"
          onClick={() => setPreviewFile(null)}
        />
      )}

      {/* 预览侧边栏 */}
      {previewFile && (
        <PreviewSidebar 
          key={`${previewFile.objectName || previewFile.fileName || previewFile.url}`}
          file={previewFile} 
          onClose={() => setPreviewFile(null)}
          getDisplayFileName={getDisplayFileName}
          requestUserId={user?.userId}
          requestRole={currentRole}
        />
      )}
      <AuthModal
        open={showAuthModal}
        onClose={() => setShowAuthModal(false)}
        onSuccess={handleLoginSuccess}
      />
      {isCameraModalOpen && (
        <div className="fixed inset-0 z-[120] bg-slate-900/55 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-4xl rounded-3xl bg-white shadow-2xl border border-slate-200 overflow-hidden">
            <div className="px-6 py-5 border-b border-slate-100 flex items-center justify-between">
              <h3 className="text-xl font-black text-slate-900">摄像头扫描入库</h3>
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
                      请将纸张完整放入绿色框内并保持平整
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
                建议：环境光充足、镜头距离纸张 25~40cm、避免反光与倾斜，可显著提升 OCR 准确率。
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
                      确认上传
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
    </Layout>
  );
}

export default App
