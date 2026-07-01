package com.py_spec_qc.core;

import com.py_spec_qc.core.config.AppConfig;
import com.py_spec_qc.core.config.ConfigLoader;
import com.py_spec_qc.core.model.FileProgress;
import com.py_spec_qc.core.model.JobStatusResponse;
import com.py_spec_qc.core.model.ProgressData;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
public final class JobManager {
    private final ConcurrentHashMap<String, JobStatusResponse> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final QualityScanner scanner = new QualityScanner();
    private static final int MAX_LOG_LINES = 200;

    public String startScan(Path reqDir, Path outDir, Path rulesDir) {
        return startScan(reqDir, outDir, rulesDir, "");
    }

    private static String msgStart(int count, String lang) {
        if ("en".equals(lang)) return "Found " + count + " files, starting scan...";
        if ("ja".equals(lang)) return count + " 個のファイルが見つかりました。スキャンを開始します...";
        return "找到 " + count + " 个文件，开始扫描...";
    }

    private static String msgScanning(String lang) {
        if ("en".equals(lang)) return "Scanning...";
        if ("ja".equals(lang)) return "スキャン中...";
        return "扫描中...";
    }

    private static String msgDone(String lang) {
        if ("en".equals(lang)) return "Scan completed!";
        if ("ja".equals(lang)) return "スキャン完了！";
        return "扫描完成！";
    }

    private static String msgFail(String error, String lang) {
        if ("en".equals(lang)) return "Failed: " + error;
        if ("ja".equals(lang)) return "失敗: " + error;
        return "失败: " + error;
    }

    private static String msgWait(String lang) {
        if ("en".equals(lang)) return "Waiting";
        if ("ja".equals(lang)) return "待機中";
        return "等待";
    }

    public String startScan(Path reqDir, Path outDir, Path rulesDir, String lang) {
        String jobId = UUID.randomUUID().toString().replace("-", "");
        List<String> files = listWordFileNames(reqDir);
        ProgressData progress = new ProgressData();
        progress.totalFiles = files.size();
        progress.scannedFiles = 0;
        progress.status = "running";
        progress.files = new ArrayList<>();
        for (String name : files) {
            FileProgress fp = new FileProgress();
            fp.fileName = name;
            fp.status = msgWait(lang);
            progress.files.add(fp);
        }

        JobStatusResponse resp = new JobStatusResponse();
        resp.status = "running";
        resp.message = msgScanning(lang);
        resp.progress = progress;
        appendLog(resp, msgStart(files.size(), lang));
        AppConfig cfg = null;
        try {
            cfg = new ConfigLoader().load();
        } catch (Exception ignored) {
        }
        String llmUrl = cfg == null ? "" : safeTrim(cfg.deepseekBaseUrl);
        String llmModel = cfg == null ? "" : safeTrim(cfg.deepseekModel);
        String llmApiKeyMasked = maskSecret(cfg == null ? "" : cfg.deepseekApiKey);
        String llmTimeoutSeconds = String.valueOf((cfg == null || cfg.deepseekTimeoutSeconds == null || cfg.deepseekTimeoutSeconds <= 0) ? 60 : cfg.deepseekTimeoutSeconds);
        String ruleChunkSize = String.valueOf((cfg == null || cfg.scanRuleChunkSize == null || cfg.scanRuleChunkSize <= 0) ? 5 : cfg.scanRuleChunkSize);
        appendFileLog(
                resolveLogPath(),
                "job start job_id=" + jobId
                        + " lang=" + (lang == null ? "" : lang.trim())
                        + " req_dir=" + (reqDir == null ? "" : reqDir.toAbsolutePath().normalize())
                        + " out_dir=" + (outDir == null ? "" : outDir.toAbsolutePath().normalize())
                        + " rules_dir=" + (rulesDir == null ? "" : rulesDir.toAbsolutePath().normalize())
                        + " file_count=" + files.size()
                        + " llm_url=" + llmUrl
                        + " llm_model=" + llmModel
                        + " llm_api_key=" + llmApiKeyMasked
                        + " llm_timeout_seconds=" + llmTimeoutSeconds
                        + " rule_chunk_size=" + ruleChunkSize
        );
        jobs.put(jobId, resp);
        locks.put(jobId, new Object());

        executor.submit(() -> runJob(jobId, reqDir, outDir, rulesDir, lang));
        return jobId;
    }

    public JobStatusResponse getJob(String jobId) {
        JobStatusResponse r = jobs.get(jobId);
        if (r == null) {
            JobStatusResponse nf = new JobStatusResponse();
            nf.status = "error";
            nf.error = "job not found";
            return nf;
        }
        return r;
    }

    private void runJob(String jobId, Path reqDir, Path outDir, Path rulesDir, String lang) {
        Object lock = Objects.requireNonNullElseGet(locks.get(jobId), Object::new);
        try {
            List<Path> outputs = scanner.scanReqDirPaths(reqDir, outDir, rulesDir, lang, update -> {
                synchronized (lock) {
                    JobStatusResponse resp = jobs.get(jobId);
                    if (resp == null || resp.progress == null) {
                        return;
                    }
                    mergeProgress(resp.progress, update);
                    resp.message = msgScanning(lang);
                    appendLog(resp, formatLogLine(update));
                    resp.status = "running";
                }
            });
            synchronized (lock) {
                JobStatusResponse resp = jobs.get(jobId);
                if (resp == null) {
                    return;
                }
                if (resp.progress != null) {
                    resp.progress.status = "complete";
                }
                resp.status = "done";
                resp.message = msgDone(lang);
                appendLog(resp, msgDone(lang));
                appendFileLog(resolveLogPath(), "job done job_id=" + jobId + " outputs=" + (outputs == null ? 0 : outputs.size()));
                List<String> outStr = new ArrayList<>();
                for (Path p : outputs) {
                    outStr.add(p.toAbsolutePath().toString());
                }
                resp.outputs = outStr;
            }
        } catch (Exception e) {
            synchronized (lock) {
                JobStatusResponse resp = jobs.get(jobId);
                if (resp == null) {
                    return;
                }
                if (resp.progress == null) {
                    resp.progress = new ProgressData();
                }
                resp.progress.status = "failed";
                resp.status = "error";
                resp.error = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                appendLog(resp, msgFail(resp.error, lang));
                appendFileLog(resolveLogPath(), "job failed job_id=" + jobId + " err=" + resp.error);
                appendFileLog(resolveLogPath(), stackTraceString(e));
            }
        }
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String maskSecret(String raw) {
        String s = safeTrim(raw);
        if (s.isBlank()) {
            return "";
        }
        if (s.length() <= 8) {
            return "*".repeat(s.length());
        }
        return s.substring(0, 4) + "*".repeat(Math.max(0, s.length() - 8)) + s.substring(s.length() - 4);
    }

    private static void mergeProgress(ProgressData progress, FileProgress update) {
        if (progress.files == null) {
            progress.files = new ArrayList<>();
        }

        if (update != null && update.fileName != null && !update.fileName.isBlank()) {
            for (FileProgress existing : progress.files) {
                if (existing != null && update.fileName.equals(existing.fileName)) {
                    copyNonNull(update, existing);
                    recomputeScanned(progress);
                    return;
                }
            }
            progress.files.add(update);
            recomputeScanned(progress);
        } else if (update != null) {
            progress.files.add(update);
            recomputeScanned(progress);
        }
    }

    private static void recomputeScanned(ProgressData progress) {
        int finished = 0;
        for (FileProgress it : progress.files) {
            if (it == null || it.status == null) {
                continue;
            }
            String st = it.status;
            if (st.contains("完成") || st.contains("失败")) {
                finished += 1;
            }
        }
        progress.scannedFiles = finished;
        progress.totalFiles = Math.max(progress.totalFiles, progress.files.size());
    }

    private static void copyNonNull(FileProgress src, FileProgress dst) {
        if (src.status != null) dst.status = src.status;
        if (src.startedAt != null) dst.startedAt = src.startedAt;
        if (src.endedAt != null) dst.endedAt = src.endedAt;
        if (src.durationMs != null) dst.durationMs = src.durationMs;
        if (src.ruleCount != null) dst.ruleCount = src.ruleCount;
        if (src.issueCount != null) dst.issueCount = src.issueCount;
        if (src.outputPath != null) dst.outputPath = src.outputPath;
        if (src.issues != null) dst.issues = src.issues;
    }

    private static String formatLogLine(FileProgress update) {
        if (update == null) {
            return "";
        }
        String name = update.fileName == null ? "" : update.fileName;
        String st = update.status == null ? "" : update.status;
        if (name.isBlank() && st.isBlank()) {
            return "";
        }
        if (name.isBlank()) {
            return st;
        }
        if (st.isBlank()) {
            return name;
        }
        return name + " - " + st;
    }

    private static void appendLog(JobStatusResponse resp, String line) {
        if (resp == null || line == null) {
            return;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return;
        }
        if (resp.logs == null) {
            resp.logs = new ArrayList<>();
        }
        resp.logs.add(t);
        if (resp.logs.size() > MAX_LOG_LINES) {
            int remove = resp.logs.size() - MAX_LOG_LINES;
            resp.logs = new ArrayList<>(resp.logs.subList(remove, resp.logs.size()));
        }
    }

    private static Path resolveLogPath() {
        String work = System.getenv("SPEC_QC_WORK_DIR");
        if (work != null && !work.trim().isEmpty()) {
            return Path.of(work.trim()).toAbsolutePath().normalize().resolve("logs").resolve("spec-qc.log");
        }
        try {
            AppConfig config = new ConfigLoader().load();
            if (config != null && config.workDir != null) {
                return config.workDir.toAbsolutePath().normalize().resolve("logs").resolve("spec-qc.log");
            }
        } catch (Exception ignored) {
        }
        return Path.of("").toAbsolutePath().normalize().resolve("work").resolve("logs").resolve("spec-qc.log");
    }

    private static void appendFileLog(Path logPath, String line) {
        if (logPath == null || line == null) {
            return;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(logPath.getParent());
            String out = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")) + " " + t + "\n";
            Files.writeString(logPath, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static String stackTraceString(Throwable t) {
        if (t == null) {
            return "";
        }
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            String s = sw.toString();
            if (s.length() > 8000) {
                return s.substring(0, 8000);
            }
            return s;
        } catch (Exception e) {
            return t.toString();
        }
    }

    private static int countWordFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try {
            return (int) Files.list(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".docx") || n.endsWith(".doc");
                    })
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<String> listWordFileNames(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try {
            return Files.list(dir)
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> {
                        String t = n.toLowerCase();
                        return t.endsWith(".docx") || t.endsWith(".doc");
                    })
                    .sorted(Comparator.comparing(String::toLowerCase))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
