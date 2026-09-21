import React, { useState, useEffect } from 'react';
import { 
  X, 
  RotateCw, 
  CheckCircle2, 
  ExternalLink, 
  Download, 
  Sparkles,
  AlertCircle,
  Package,
  HardDrive
} from 'lucide-react';

interface UpdateModalProps {
  isOpen: boolean;
  onClose: () => void;
  currentVersion: string;
}

interface ReleaseInfo {
  version: string;
  tagName: string;
  name: string;
  body: string;
  publishedAt: string;
  htmlUrl: string;
  hasUpdate: boolean;
}

type UpdatePhase = 'idle' | 'downloading' | 'verifying' | 'installing' | 'relaunching' | 'completed';

export const UpdateModal: React.FC<UpdateModalProps> = ({
  isOpen,
  onClose,
  currentVersion,
}) => {
  const [isChecking, setIsChecking] = useState(false);
  const [isInstalling, setIsInstalling] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const [downloadBytes, setDownloadBytes] = useState(0);
  const [totalBytes, setTotalBytes] = useState(0);
  const [updatePhase, setUpdatePhase] = useState<UpdatePhase>('idle');
  const [releaseInfo, setReleaseInfo] = useState<ReleaseInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      checkForUpdates();
    } else {
      setIsInstalling(false);
      setUpdatePhase('idle');
      setDownloadProgress(0);
    }
  }, [isOpen]);

  const compareVersions = (v1: string, v2: string): number => {
    const clean1 = v1.replace(/^v/, '').split('.').map(Number);
    const clean2 = v2.replace(/^v/, '').split('.').map(Number);
    for (let i = 0; i < Math.max(clean1.length, clean2.length); i++) {
      const num1 = clean1[i] || 0;
      const num2 = clean2[i] || 0;
      if (num2 > num1) return 1;
      if (num1 > num2) return -1;
    }
    return 0;
  };

  const checkForUpdates = async () => {
    setIsChecking(true);
    setError(null);

    const isTauri = typeof window !== 'undefined' && Boolean((window as any).__TAURI_INTERNALS__);

    // 1. Primary check via Tauri updater plugin if available
    if (isTauri) {
      try {
        const { check } = await import('@tauri-apps/plugin-updater');
        const update = await check();
        if (update) {
          setReleaseInfo({
            version: update.version,
            tagName: `v${update.version}`,
            name: `FireFiles v${update.version}`,
            body: update.body || 'New improvements and bug fixes.',
            publishedAt: new Date(update.date || Date.now()).toLocaleDateString(),
            htmlUrl: 'https://github.com/endrisusanto/Files/releases',
            hasUpdate: true,
          });
          setIsChecking(false);
          return;
        } else {
          let activeVer = currentVersion;
          try {
            const { getVersion } = await import('@tauri-apps/api/app');
            const v = await getVersion();
            if (v) activeVer = v;
          } catch {}

          setReleaseInfo({
            version: activeVer,
            tagName: `v${activeVer}`,
            name: `FireFiles v${activeVer}`,
            body: 'You are running the latest version.',
            publishedAt: new Date().toLocaleDateString(),
            htmlUrl: 'https://github.com/endrisusanto/Files/releases',
            hasUpdate: false,
          });
          setIsChecking(false);
          return;
        }
      } catch {
        // Fallback to GitHub API
      }
    }

    // 2. Direct GitHub API check
    try {
      let activeVer = currentVersion;
      if (isTauri) {
        try {
          const { getVersion } = await import('@tauri-apps/api/app');
          const v = await getVersion();
          if (v) activeVer = v;
        } catch {}
      }

      let latestTag = '';
      let releaseBody = 'Bug fixes and performance improvements.';
      let publishedDate = new Date().toLocaleDateString();

      // Query GitHub Releases API
      try {
        const res = await fetch('https://api.github.com/repos/endrisusanto/Files/releases/latest', {
          headers: { Accept: 'application/vnd.github.v3+json' },
        });

        if (res.ok) {
          const data = await res.json();
          latestTag = (data.tag_name || data.name || '').replace(/^v/, '');
          if (data.body) releaseBody = data.body;
          if (data.published_at) publishedDate = new Date(data.published_at).toLocaleDateString();
        } else if (res.status === 404) {
          latestTag = activeVer;
          releaseBody = 'No newer public releases published yet.';
        }
      } catch (apiErr) {
        console.warn('Direct GitHub API fetch error:', apiErr);
      }

      // Fallback: fetch raw package.json from main branch
      if (!latestTag) {
        try {
          const rawRes = await fetch('https://raw.githubusercontent.com/endrisusanto/Files/main/package.json', {
            cache: 'no-store',
          });
          if (rawRes.ok) {
            const rawPkg = await rawRes.json();
            if (rawPkg.version) {
              latestTag = rawPkg.version.replace(/^v/, '');
              releaseBody = `Version v${latestTag} available from repository main branch.`;
            }
          }
        } catch (rawErr) {
          console.warn('Raw package.json fetch error:', rawErr);
        }
      }

      if (!latestTag) {
        setReleaseInfo({
          version: activeVer,
          tagName: `v${activeVer}`,
          name: `FireFiles v${activeVer}`,
          body: 'Unable to connect to GitHub update service. You can check releases manually.',
          publishedAt: new Date().toLocaleDateString(),
          htmlUrl: 'https://github.com/endrisusanto/Files/releases',
          hasUpdate: false,
        });
        setIsChecking(false);
        return;
      }

      const hasUpdate = compareVersions(activeVer, latestTag) > 0;

      setReleaseInfo({
        version: latestTag,
        tagName: `v${latestTag}`,
        name: `FireFiles v${latestTag}`,
        body: releaseBody,
        publishedAt: publishedDate,
        htmlUrl: 'https://github.com/endrisusanto/Files/releases',
        hasUpdate,
      });
    } catch (err: any) {
      setError(`Unable to check for updates: ${err.message || err}`);
    } finally {
      setIsChecking(false);
    }
  };

  const handleInstallUpdate = async () => {
    setIsInstalling(true);
    setError(null);
    setDownloadProgress(5);
    setUpdatePhase('downloading');

    const isTauri = typeof window !== 'undefined' && Boolean((window as any).__TAURI_INTERNALS__);

    try {
      if (isTauri) {
        try {
          const { check } = await import('@tauri-apps/plugin-updater');
          const { relaunch } = await import('@tauri-apps/plugin-process');

          const update = await check();
          if (update) {
            setDownloadProgress(10);
            setUpdatePhase('downloading');

            let currentDownloaded = 0;
            let totalContentLength = 0;

            await update.downloadAndInstall((event: any) => {
              switch (event.event) {
                case 'Started':
                  totalContentLength = event.data.contentLength || 15 * 1024 * 1024;
                  setTotalBytes(totalContentLength);
                  setUpdatePhase('downloading');
                  break;
                case 'Progress':
                  currentDownloaded += event.data.chunkLength;
                  setDownloadBytes(currentDownloaded);
                  if (totalContentLength > 0) {
                    const pct = Math.min(92, Math.round((currentDownloaded / totalContentLength) * 90));
                    setDownloadProgress(pct);
                  }
                  break;
                case 'Finished':
                  setDownloadProgress(95);
                  setUpdatePhase('installing');
                  break;
              }
            });

            setDownloadProgress(100);
            setUpdatePhase('relaunching');
            await new Promise(r => setTimeout(r, 600));
            await relaunch();
            return;
          }
        } catch (tErr: any) {
          console.warn('Tauri updater failed, opening release page:', tErr);
        }
      }
    } catch (e: any) {
      console.warn('Direct installer error:', e);
      setError(`Automatic install notice: ${e.message}. Opening GitHub releases...`);
    }

    // Fallback: open GitHub release download page
    if (releaseInfo?.htmlUrl) {
      window.open(releaseInfo.htmlUrl, '_blank', 'noopener,noreferrer');
    }
    setIsInstalling(false);
    setUpdatePhase('idle');
  };

  if (!isOpen) return null;

  const isTauri = typeof window !== 'undefined' && Boolean((window as any).__TAURI_INTERNALS__);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/65 backdrop-blur-md p-4 animate-in fade-in duration-200">
      <div 
        className="w-full max-w-md rounded-2xl border border-gray-200 dark:border-zinc-800 bg-white dark:bg-[#121215] shadow-2xl overflow-hidden flex flex-col transition-all duration-300"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="p-4 px-5 border-b border-gray-150 dark:border-zinc-800/80 flex items-center justify-between bg-gray-50/50 dark:bg-zinc-900/40">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-orange-500/10 border border-orange-500/20 flex items-center justify-center text-orange-500">
              <Package className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-gray-900 dark:text-zinc-100 flex items-center gap-2">
                Software Updates
                {releaseInfo?.hasUpdate && (
                  <span className="text-[10px] font-semibold bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20 px-2 py-0.5 rounded-full">
                    Update Ready
                  </span>
                )}
              </h3>
              <p className="text-[11px] text-gray-500 dark:text-zinc-400">
                FireFiles Engine & Cross-Network Bridge
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            disabled={isInstalling}
            className="p-1.5 rounded-lg text-gray-400 hover:text-gray-600 dark:hover:text-zinc-200 hover:bg-gray-100 dark:hover:bg-zinc-800 transition disabled:opacity-40 cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="p-5 space-y-4">
          {isInstalling ? (
            /* INSTALLING PROGRESS VIEW */
            <div className="py-6 flex flex-col items-center justify-center text-center space-y-4">
              <div className="relative">
                <div className="w-16 h-16 rounded-full border-4 border-orange-500/20 border-t-orange-500 animate-spin flex items-center justify-center">
                </div>
                <div className="absolute inset-0 flex items-center justify-center text-xs font-bold text-orange-500">
                  {downloadProgress}%
                </div>
              </div>

              <div className="space-y-1">
                <h4 className="text-sm font-bold text-gray-900 dark:text-zinc-100 capitalize">
                  {updatePhase === 'downloading' && 'Downloading Update Package...'}
                  {updatePhase === 'verifying' && 'Verifying Integrity...'}
                  {updatePhase === 'installing' && 'Installing New Version...'}
                  {updatePhase === 'relaunching' && 'Restarting Application...'}
                  {updatePhase === 'completed' && 'Update Complete!'}
                </h4>
                <p className="text-xs text-gray-500 dark:text-zinc-400 font-mono">
                  {totalBytes > 0 
                    ? `${(downloadBytes / (1024 * 1024)).toFixed(1)} MB / ${(totalBytes / (1024 * 1024)).toFixed(1)} MB`
                    : 'Applying latest release assets...'}
                </p>
              </div>

              <div className="w-full bg-gray-100 dark:bg-zinc-800 rounded-full h-2 overflow-hidden">
                <div 
                  className="bg-gradient-to-r from-orange-500 to-amber-500 h-full transition-all duration-300 ease-out rounded-full"
                  style={{ width: `${downloadProgress}%` }}
                />
              </div>

              <div className="text-[11px] text-gray-400 dark:text-zinc-500">
                Please wait while the auto-installer completes the update process.
              </div>
            </div>
          ) : (
            /* REGULAR UPDATE CHECKER & INFO VIEW */
            <>
              {/* Status Box */}
              <div className="p-4 rounded-xl bg-gray-50 dark:bg-zinc-900/60 border border-gray-200/80 dark:border-zinc-800 flex flex-col gap-2.5">
                <div className="flex items-center justify-between text-xs font-semibold">
                  <span className="text-gray-500 dark:text-zinc-400">Current Installed:</span>
                  <span className="font-mono bg-gray-200/70 dark:bg-zinc-800 px-2 py-0.5 rounded text-gray-800 dark:text-zinc-200">
                    v{currentVersion}
                  </span>
                </div>

                <div className="flex items-center justify-between text-xs font-semibold">
                  <span className="text-gray-500 dark:text-zinc-400">Latest Release:</span>
                  <span className="font-mono bg-blue-50 dark:bg-blue-950/80 text-blue-600 dark:text-blue-300 px-2 py-0.5 rounded border border-blue-200/60 dark:border-blue-800/60">
                    {isChecking ? 'Checking...' : releaseInfo ? `v${releaseInfo.version}` : '-'}
                  </span>
                </div>

                {/* Status Message */}
                <div className="pt-2 border-t border-gray-200/60 dark:border-zinc-800/60 flex items-center gap-2 text-xs">
                  {isChecking ? (
                    <div className="flex items-center gap-2 text-orange-500 font-medium">
                      <RotateCw className="w-3.5 h-3.5 animate-spin" />
                      <span>Checking GitHub repository...</span>
                    </div>
                  ) : releaseInfo?.hasUpdate ? (
                    <div className="flex items-center gap-1.5 text-emerald-600 dark:text-emerald-400 font-bold">
                      <Sparkles className="w-4 h-4 text-amber-500" />
                      <span>New version available (v{releaseInfo.version})!</span>
                    </div>
                  ) : (
                    <div className="flex items-center gap-1.5 text-gray-600 dark:text-zinc-300 font-medium">
                      <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                      <span>You are running the latest version.</span>
                    </div>
                  )}
                </div>
              </div>

              {/* Changelog / Release Notes */}
              {releaseInfo?.hasUpdate && releaseInfo.body && (
                <div className="space-y-1.5">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-gray-400 dark:text-zinc-500">
                    What's New in v{releaseInfo.version}:
                  </span>
                  <div className="p-3 rounded-xl bg-gray-50 dark:bg-zinc-950/80 border border-gray-200/70 dark:border-zinc-800 text-xs font-mono text-gray-700 dark:text-zinc-300 max-h-36 overflow-y-auto whitespace-pre-line leading-relaxed">
                    {releaseInfo.body}
                  </div>
                </div>
              )}

              {/* Error notice */}
              {error && (
                <div className="p-2.5 rounded-lg bg-rose-50 dark:bg-rose-950/50 border border-rose-200 dark:border-rose-800 flex items-center gap-2 text-xs text-rose-700 dark:text-rose-300">
                  <AlertCircle className="w-4 h-4 shrink-0" />
                  <span>{error}</span>
                </div>
              )}

              {/* Actions */}
              <div className="pt-2 border-t border-gray-200 dark:border-zinc-800 flex items-center justify-between gap-2">
                <button
                  onClick={checkForUpdates}
                  disabled={isChecking || isInstalling}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-gray-600 dark:text-zinc-400 hover:bg-gray-100 dark:hover:bg-zinc-800 rounded-lg transition-colors disabled:opacity-50 cursor-pointer"
                >
                  <RotateCw className={`w-3.5 h-3.5 ${isChecking ? 'animate-spin' : ''}`} />
                  <span>Refresh</span>
                </button>

                <div className="flex items-center gap-2">
                  {releaseInfo?.hasUpdate ? (
                    <button
                      onClick={handleInstallUpdate}
                      disabled={isInstalling}
                      className="flex items-center gap-1.5 px-4 py-1.5 text-xs font-semibold text-white bg-gradient-to-r from-orange-500 to-amber-600 hover:from-orange-600 hover:to-amber-700 active:scale-95 rounded-lg shadow-sm shadow-orange-500/20 transition-all disabled:opacity-50 cursor-pointer"
                    >
                      <Download className="w-3.5 h-3.5" />
                      <span>{isTauri ? 'Install Update' : 'Get Update'}</span>
                    </button>
                  ) : (
                    <a
                      href="https://github.com/endrisusanto/Files/releases"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/60 rounded-lg transition-colors cursor-pointer"
                    >
                      <span>Releases</span>
                      <ExternalLink className="w-3 h-3" />
                    </a>
                  )}
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
