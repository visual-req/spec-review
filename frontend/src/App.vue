<template>
  <div class="container">
    <div class="card">
      <div class="row">
        <div class="title">{{ t('app.title') }}</div>
        <div style="display:flex; gap: 8px;">
          <select class="btn" v-model="lang" :disabled="isScanning" style="max-width: 120px;">
            <option value="zh">中文</option>
            <option value="ja">日本語</option>
            <option value="en">English</option>
          </select>
          <div class="notif-wrap">
            <button class="btn notif-btn" :disabled="!notifications.length" @click="toggleNotif">
              {{ t('btn.notifications') }}
              <span v-if="notifications.length" class="badge">{{ notifications.length }}</span>
            </button>
            <div v-if="notifOpen" class="notif-panel">
              <div v-if="!notifications.length" class="empty" style="padding: 10px;">{{ t('empty.noNotifications') }}</div>
              <div v-else>
                <div v-for="n in notifications" :key="n.id" class="notif-item" @click="openNotif(n)">
                  <div class="notif-title">{{ n.title }}</div>
                  <div class="notif-sub mono">{{ n.file }} #{{ n.seq }}</div>
                </div>
              </div>
            </div>
          </div>
          <button class="btn" @click="showConfig = !showConfig">{{ showConfig ? t('btn.hideConfig') : t('btn.showConfig') }}</button>
          <button class="btn btn-primary" :disabled="isScanning" @click="startScan">{{ t('btn.startScan') }}</button>
        </div>
      </div>

      <div class="spacer"></div>

      <div v-show="showConfig">
        <div style="display:flex; gap: 8px; flex-wrap: wrap;">
          <button class="btn" :class="openMode === 'local' ? 'btn-primary' : ''" :disabled="isScanning" @click="openMode = 'local'">{{ t('btn.localDir') }}</button>
          <button class="btn" :class="openMode === 'upload' ? 'btn-primary' : ''" :disabled="isScanning" @click="openMode = 'upload'">{{ t('btn.uploadFiles') }}</button>
        </div>
        <div class="spacer"></div>
        <div class="llm-config-box">
          <div class="llm-config-title">{{ t('section.llmConfig') }}</div>
          <div class="llm-config-grid mono">
            <div class="llm-config-item">
              <div class="k">{{ t('llm.url') }}</div>
              <div class="v">{{ llmRuntime.url || '-' }}</div>
            </div>
            <div class="llm-config-item">
              <div class="k">{{ t('llm.model') }}</div>
              <div class="v">{{ llmRuntime.model || '-' }}</div>
            </div>
            <div class="llm-config-item">
              <div class="k">{{ t('llm.authMode') }}</div>
              <div class="v">{{ llmRuntime.authMode || '-' }}</div>
            </div>
            <div class="llm-config-item">
              <div class="k">{{ t('llm.apiKey') }}</div>
              <div class="v">{{ llmRuntime.apiKeyMasked || '-' }}</div>
            </div>
            <div class="llm-config-item">
              <div class="k">{{ t('llm.timeoutSeconds') }}</div>
              <div class="v">{{ llmRuntime.timeoutSeconds || '-' }}</div>
            </div>
            <div class="llm-config-item">
              <div class="k">{{ t('llm.ruleChunkSize') }}</div>
              <div class="v">{{ llmRuntime.ruleChunkSize || '-' }}</div>
            </div>
            <div class="llm-config-item llm-config-item-wide">
              <div class="k">{{ t('llm.configPath') }}</div>
              <div class="v">{{ llmRuntime.configPath || '-' }}</div>
            </div>
            <div v-if="llmRuntime.error" class="llm-config-item llm-config-item-wide">
              <div class="k">{{ t('llm.loadError') }}</div>
              <div class="v">{{ llmRuntime.error }}</div>
            </div>
          </div>
        </div>
        <div class="spacer"></div>

        <div v-if="openMode === 'local'">
          <div>
            <label>{{ t('label.reqDirLocal') }}</label>
            <div class="input-row">
              <input v-model="reqDir" :placeholder="t('placeholder.absPath')" />
              <button class="btn" :disabled="isScanning" @click="pickDir('reqDir')">{{ t('btn.chooseFolder') }}</button>
              <button class="btn" :disabled="isScanning || !reqDir" @click="reqDir = ''">{{ t('btn.clear') }}</button>
            </div>
          </div>
          <div class="spacer"></div>
          <div>
            <label>{{ t('label.outDir') }}</label>
            <div class="input-row">
              <input v-model="outDir" :placeholder="t('placeholder.outDir')" />
              <button class="btn" :disabled="isScanning" @click="pickDir('outDir')">{{ t('btn.chooseFolder') }}</button>
              <button class="btn" :disabled="isScanning || !outDir" @click="outDir = ''">{{ t('btn.clear') }}</button>
            </div>
          </div>
          <div class="spacer"></div>
          <div>
            <label>{{ t('label.rulesDir') }}</label>
            <div class="input-row">
              <input v-model="rulesDir" :placeholder="t('placeholder.rulesDir')" />
              <button class="btn" :disabled="isScanning" @click="pickDir('rulesDir')">{{ t('btn.chooseFolder') }}</button>
              <button class="btn" :disabled="isScanning || !rulesDir" @click="rulesDir = ''">{{ t('btn.clear') }}</button>
            </div>
          </div>
        </div>

        <div v-else>
          <div>
            <label>{{ t('label.uploadFiles') }}</label>
            <div class="input-row">
              <input :value="uploadSummary" readonly :placeholder="t('placeholder.noFiles')" />
              <button class="btn" :disabled="isScanning" @click="pickReqFiles">{{ t('btn.chooseFiles') }}</button>
              <button class="btn" :disabled="isScanning || !reqFiles.length" @click="clearReqFiles">{{ t('btn.clear') }}</button>
            </div>
            <input ref="reqPicker" type="file" multiple accept=".doc,.docx" style="display:none" @change="onReqFilesChange" />
          </div>
          <div class="spacer"></div>
          <div class="mono" style="font-size: 12px; opacity: 0.85;">
            {{ t('hint.uploadExplain') }}
          </div>
        </div>
      </div>
    </div>

    <div class="spacer"></div>

    <div class="card">
      <div class="row">
        <div style="font-size: 14px; font-weight: 600;">{{ t('section.summary') }}</div>
        <div style="display:flex; gap: 8px;">
          <button class="btn" @click="showProgress = !showProgress">{{ showProgress ? t('btn.collapseList') : t('btn.expandList') }}</button>
          <button class="btn" @click="showLogs = !showLogs">{{ showLogs ? t('btn.collapseLogs') : t('btn.expandLogs') }}</button>
        </div>
      </div>
      <div class="spacer"></div>
      <div class="stats">
        <div class="stat"><div class="k">{{ t('stat.totalFiles') }}</div><div class="v">{{ summary.totalFiles }}</div></div>
        <div class="stat"><div class="k">{{ t('stat.scannedFiles') }}</div><div class="v">{{ summary.scannedFiles }}</div></div>
        <div class="stat"><div class="k">{{ t('stat.totalIssues') }}</div><div class="v">{{ summary.totalIssues }}</div></div>
        <div class="stat"><div class="k">{{ t('stat.scanStatus') }}</div><div class="v">{{ scanStatusText }}</div></div>
      </div>
      <div v-show="showProgress">
        <div class="spacer"></div>
        <div class="table-wrap">
          <div class="table-scroll progress-scroll">
            <table>
              <thead>
                <tr>
                  <th>{{ t('table.fileName') }}</th>
                  <th style="width: 90px;">{{ t('table.status') }}</th>
                  <th style="width: 170px;">{{ t('table.startTime') }}</th>
                  <th style="width: 170px;">{{ t('table.endTime') }}</th>
                  <th style="width: 110px;">{{ t('table.duration') }}</th>
                  <th style="width: 90px;">{{ t('table.ruleCount') }}</th>
                  <th style="width: 90px;">{{ t('table.issueCount') }}</th>
                  <th style="width: 90px;">{{ t('table.output') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="it in progressFiles" :key="it.file_name" @click="selectFile(it.file_name)" style="cursor:pointer;">
                  <td>{{ it.file_name }}</td>
                  <td>
                    <span class="tag" :class="statusTagClass(it.status)">{{ statusTagText(it.status) }}</span>
                    <div v-if="failureReason(it.status)" class="mono" style="margin-top: 4px; font-size: 12px; opacity: 0.85;">
                      {{ failureReason(it.status) }}
                    </div>
                  </td>
                  <td>{{ formatLocalTime(it.started_at) }}</td>
                  <td>{{ formatLocalTime(it.ended_at) }}</td>
                  <td>{{ formatDuration(it.duration_ms) }}</td>
                  <td>{{ it.rule_count ?? 0 }}</td>
                  <td>{{ it.issue_count ?? 0 }}</td>
                  <td>
                    <button class="btn" :disabled="!it.output_path || !jobId" @click.stop="download(it.file_name)">
                      {{ t('btn.download') }}
                    </button>
                  </td>
                </tr>
                <tr v-if="!progressFiles.length">
                  <td colspan="8" class="empty">{{ t('empty.noData') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <div class="spacer"></div>
      </div>
      <div v-show="showLogs" class="status mono status-scroll">{{ statusText }}</div>
    </div>

    <div class="spacer"></div>

    <div class="card">
      <div style="font-size: 14px; font-weight: 600; margin-bottom: 10px;">{{ t('section.issues') }}</div>
      <div style="display:flex; gap: 8px; align-items:center; flex-wrap: wrap;">
        <label style="margin:0;">{{ t('issue.filterFile') }}</label>
        <select class="btn" v-model="selectedFile" @change="onSelectedFileChange">
          <option value="__ALL__">{{ t('btn.filterAll') }}</option>
          <option v-for="name in fileOptions" :key="name" :value="name">{{ name }}</option>
        </select>
        <button class="btn" :disabled="!displayedIssues.length" @click="collapseAllIssues">{{ t('btn.collapseAll') }}</button>
        <div v-if="displayedIssues.length" style="display:flex; gap: 8px; align-items:center;">
          <button class="btn" :disabled="issuePage <= 1" @click="prevIssuePage">{{ t('btn.prevPage') }}</button>
          <div class="mono" style="font-size: 12px;">{{ t('issue.pageInfo', { page: issuePage, total: issueTotalPages, count: displayedIssues.length }) }}</div>
          <button class="btn" :disabled="issuePage >= issueTotalPages" @click="nextIssuePage">{{ t('btn.nextPage') }}</button>
        </div>
      </div>
      <div class="spacer"></div>
      <div class="issue-list-wrap">
        <div v-if="!displayedIssues.length" class="empty">{{ t('empty.noData') }}</div>
        <div v-else class="issue-list">
          <div v-for="row in pagedIssues" :key="row.__key" class="issue-item" :data-key="row.__key" :class="row.__key === highlightKey ? 'issue-item-highlight' : ''">
            <div class="issue-head">
              <div class="issue-title">
                <button class="btn btn-sm" @click.stop="toggleIssue(row)">{{ isIssueOpen(row) ? t('btn.collapse') : t('btn.expand') }}</button>
                <span class="tag" :class="severityTagClass(row.severity)">{{ severityText(row.severity) }}</span>
                <span style="margin-left:8px;">#{{ row.seq }} {{ row.category }}</span>
                <span v-if="row.evidence_section" class="issue-section mono">{{ row.evidence_section }}</span>
              </div>
              <div style="display:flex; gap: 8px; align-items:center;">
                <span class="tag" :class="reviewTagClass(row.review_status)">{{ reviewTagText(row.review_status) }}</span>
                <button class="btn" :disabled="!jobId || !isFileReviewable(row.__file) || isReviewBusy(row)" @click="decide(row, 'reject')">{{ isReviewBusy(row) ? t('btn.processing') : t('btn.reject') }}</button>
                <button class="btn btn-primary" :disabled="!jobId || !isFileReviewable(row.__file) || row.review_status === 'accepted' || isReviewBusy(row)" @click="decide(row, 'accept')">{{ isReviewBusy(row) ? t('btn.processing') : t('btn.accept') }}</button>
                <div v-if="selectedFile === '__ALL__'" class="issue-file">{{ row.__file }}</div>
              </div>
            </div>
            <div class="issue-body" v-show="isIssueOpen(row)">
              <div class="issue-row">
                <div class="kv inline">
                  <div class="k">{{ t('issue.page') }}</div>
                  <div class="v">{{ row.evidence_page }}</div>
                </div>
                <div class="kv inline">
                  <div class="k">{{ t('issue.section') }}</div>
                  <div class="v">{{ row.evidence_section }}</div>
                </div>
              </div>
              <div class="issue-row">
                <div class="kv inline">
                  <div class="k">{{ t('issue.description') }}</div>
                  <div class="v">{{ row.description }}</div>
                </div>
                <div class="kv inline">
                  <div class="k">{{ t('issue.relatedStandard') }}</div>
                  <div class="v mono">{{ row.related_standard }}</div>
                </div>
              </div>
              <div class="issue-row one-col">
                <div class="kv">
                  <div class="k">{{ t('issue.evidence') }}</div>
                  <div class="v" v-html="renderEvidenceHtml(row)"></div>
                </div>
              </div>
              <div class="issue-row one-col">
                <div class="kv">
                  <div class="k">{{ t('issue.suggestion') }}</div>
                  <div class="v" v-html="renderSuggestionHtml(row)"></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const lang = ref(loadLang())

const dict = {
  zh: {
    'app.title': 'AI需求质量扫描',
    'btn.notifications': '提醒',
    'btn.hideConfig': '收起配置',
    'btn.showConfig': '展开配置',
    'btn.startScan': '开始扫描',
    'btn.localDir': '本机目录',
    'btn.uploadFiles': '上传文件',
    'btn.collapse': '收起',
    'btn.expand': '展开',
    'btn.chooseFolder': '选择文件夹',
    'btn.up': '上一级',
    'btn.chooseThisDir': '选择此目录',
    'btn.chooseFiles': '选择文件',
    'btn.clear': '清空',
    'btn.collapseList': '收起列表',
    'btn.expandList': '展开列表',
    'btn.collapseLogs': '收起日志',
    'btn.expandLogs': '展开日志',
    'btn.download': '下载',
    'btn.filterAll': '全部',
    'btn.collapseAll': '折叠全部',
    'btn.prevPage': '上一页',
    'btn.nextPage': '下一页',
    'btn.reject': '拒绝',
    'btn.accept': '接受',
    'btn.processing': '处理中',
    'label.reqDirLocal': '待评审需求目录（本机目录）',
    'label.outDir': '需求评审结果目录（可选，默认：work/output）',
    'label.rulesDir': '规则目录（可选，默认：work/quality）',
    'label.uploadFiles': '上传待扫描文件（.docx/.doc）',
    'placeholder.absPath': '请填写绝对路径',
    'placeholder.outDir': '请填写绝对路径（留空则使用默认输出目录）',
    'placeholder.rulesDir': '请填写绝对路径（留空则使用默认规则目录）',
    'placeholder.noFiles': '未选择文件',
    'hint.uploadExplain': '上传文件会保存到默认 input 目录，扫描结果输出到默认 output 目录，文件名会追加时间戳避免覆盖',
    'section.summary': '扫描汇总',
    'section.issues': '问题查看',
    'section.llmConfig': '模型访问参数',
    'stat.totalFiles': '总文件数',
    'stat.scannedFiles': '已扫描文件数',
    'stat.totalIssues': '发现问题总数',
    'stat.scanStatus': '扫描状态',
    'table.fileName': '文件名',
    'table.status': '状态',
    'table.startTime': '开始时间',
    'table.endTime': '结束时间',
    'table.duration': '总时长',
    'table.ruleCount': '规则数',
    'table.issueCount': '问题数',
    'table.output': '输出',
    'empty.noNotifications': '暂无提醒',
    'empty.noData': '暂无数据',
    'empty.noSubDirs': '无子目录',
    'issue.filterFile': '文件筛选',
    'issue.pageInfo': '第 {page} / {total} 页（共 {count} 条）',
    'issue.page': '页号',
    'issue.section': '章节编号',
    'issue.description': '问题描述',
    'issue.relatedStandard': '关联标准',
    'issue.evidence': '内容摘录',
    'issue.suggestion': '建议',
    'severity.high': '高',
    'severity.medium': '中',
    'severity.low': '低',
    'tag.failed': '失败',
    'tag.completed': '完成',
    'tag.started': '开始',
    'tag.scanning': '扫描中',
    'tag.reviewPending': '待处理',
    'tag.reviewAccepted': '已接受',
    'tag.reviewRejected': '已拒绝',
    'scan.status.running': '扫描中',
    'scan.status.done': '已完成',
    'scan.status.failed': '失败',
    'scan.status.notStarted': '未开始',
    'msg.pickUploadFiles': '请选择要上传的需求文件',
    'msg.fillReqDir': '请填写待评审需求目录',
    'msg.startingScan': '启动扫描...',
    'msg.startFailed': '启动失败',
    'msg.readDirFailedPrefix': '读取目录失败：',
    'msg.revisedWrittenPrefix': '已写入修订版 Word：',
    'msg.failPrefix': '失败: ',
    'msg.runtimeConfigHeader': '当前模型访问参数',
    'notif.newIssue': '发现新问题：{category}（{severity}）',
    'llm.url': 'LLM URL',
    'llm.model': '模型名',
    'llm.authMode': '鉴权方式',
    'llm.apiKey': 'API Key',
    'llm.timeoutSeconds': '超时时间(秒)',
    'llm.ruleChunkSize': '规则批次大小',
    'llm.configPath': '配置文件',
    'llm.loadError': '读取失败',
    'time.h': '小时',
    'time.m': '分钟',
    'time.s': '秒',
  },
  en: {
    'app.title': 'AI Spec Quality Scan',
    'btn.notifications': 'Alerts',
    'btn.hideConfig': 'Hide',
    'btn.showConfig': 'Config',
    'btn.startScan': 'Start',
    'btn.localDir': 'Local Folder',
    'btn.uploadFiles': 'Upload Files',
    'btn.collapse': 'Collapse',
    'btn.expand': 'Expand',
    'btn.chooseFolder': 'Choose',
    'btn.up': 'Up',
    'btn.chooseThisDir': 'Use Folder',
    'btn.chooseFiles': 'Choose Files',
    'btn.clear': 'Clear',
    'btn.collapseList': 'Hide List',
    'btn.expandList': 'Show List',
    'btn.collapseLogs': 'Hide Logs',
    'btn.expandLogs': 'Show Logs',
    'btn.download': 'Download',
    'btn.filterAll': 'All',
    'btn.collapseAll': 'Collapse All',
    'btn.prevPage': 'Prev',
    'btn.nextPage': 'Next',
    'btn.reject': 'Reject',
    'btn.accept': 'Accept',
    'btn.processing': 'Working',
    'label.reqDirLocal': 'Requirements Folder (Local)',
    'label.outDir': 'Output Folder (Optional, default: work/output)',
    'label.rulesDir': 'Rules Folder (Optional, default: work/quality)',
    'label.uploadFiles': 'Upload Requirements (.docx/.doc)',
    'placeholder.absPath': 'Absolute path',
    'placeholder.outDir': 'Absolute path (leave empty for default)',
    'placeholder.rulesDir': 'Absolute path (leave empty for default)',
    'placeholder.noFiles': 'No file selected',
    'hint.uploadExplain': 'Uploaded files are saved to the default input folder. Results are written to the default output folder. Filenames are suffixed with a timestamp to avoid overwrite.',
    'section.summary': 'Summary',
    'section.issues': 'Issues',
    'section.llmConfig': 'LLM Runtime Config',
    'stat.totalFiles': 'Total Files',
    'stat.scannedFiles': 'Scanned Files',
    'stat.totalIssues': 'Total Issues',
    'stat.scanStatus': 'Status',
    'table.fileName': 'File',
    'table.status': 'Status',
    'table.startTime': 'Start',
    'table.endTime': 'End',
    'table.duration': 'Duration',
    'table.ruleCount': 'Rules',
    'table.issueCount': 'Issues',
    'table.output': 'Output',
    'empty.noNotifications': 'No alerts',
    'empty.noData': 'No data',
    'empty.noSubDirs': 'No subfolders',
    'issue.filterFile': 'File',
    'issue.pageInfo': 'Page {page} / {total} (Total {count})',
    'issue.page': 'Page',
    'issue.section': 'Section',
    'issue.description': 'Description',
    'issue.relatedStandard': 'Related Standard',
    'issue.evidence': 'Evidence',
    'issue.suggestion': 'Suggestion',
    'severity.high': 'High',
    'severity.medium': 'Medium',
    'severity.low': 'Low',
    'tag.failed': 'Failed',
    'tag.completed': 'Done',
    'tag.started': 'Started',
    'tag.scanning': 'Running',
    'tag.reviewPending': 'Pending',
    'tag.reviewAccepted': 'Accepted',
    'tag.reviewRejected': 'Rejected',
    'scan.status.running': 'Running',
    'scan.status.done': 'Done',
    'scan.status.failed': 'Failed',
    'scan.status.notStarted': 'Not started',
    'msg.pickUploadFiles': 'Please choose files to upload',
    'msg.fillReqDir': 'Please enter the requirements folder',
    'msg.startingScan': 'Starting...',
    'msg.startFailed': 'Failed to start',
    'msg.readDirFailedPrefix': 'Failed to read folder: ',
    'msg.revisedWrittenPrefix': 'Revised Word written: ',
    'msg.failPrefix': 'Failed: ',
    'msg.runtimeConfigHeader': 'Current LLM runtime config',
    'notif.newIssue': 'New issue: {category} ({severity})',
    'llm.url': 'LLM URL',
    'llm.model': 'Model',
    'llm.authMode': 'Auth',
    'llm.apiKey': 'API Key',
    'llm.timeoutSeconds': 'Timeout (s)',
    'llm.ruleChunkSize': 'Rule Batch Size',
    'llm.configPath': 'Config File',
    'llm.loadError': 'Load Error',
    'time.h': 'h',
    'time.m': 'm',
    'time.s': 's',
  },
  ja: {
    'app.title': 'AI要件品質スキャン',
    'btn.notifications': '通知',
    'btn.hideConfig': '設定を閉じる',
    'btn.showConfig': '設定を開く',
    'btn.startScan': 'スキャン開始',
    'btn.localDir': 'ローカル',
    'btn.uploadFiles': 'アップロード',
    'btn.collapse': '折りたたむ',
    'btn.expand': '展開',
    'btn.chooseFolder': 'フォルダ選択',
    'btn.up': '上へ',
    'btn.chooseThisDir': 'このフォルダを使用',
    'btn.chooseFiles': 'ファイル選択',
    'btn.clear': 'クリア',
    'btn.collapseList': '一覧を閉じる',
    'btn.expandList': '一覧を開く',
    'btn.collapseLogs': 'ログを閉じる',
    'btn.expandLogs': 'ログを開く',
    'btn.download': 'ダウンロード',
    'btn.filterAll': 'すべて',
    'btn.collapseAll': 'すべて折りたたむ',
    'btn.prevPage': '前へ',
    'btn.nextPage': '次へ',
    'btn.reject': '却下',
    'btn.accept': '承認',
    'btn.processing': '処理中',
    'label.reqDirLocal': '要件フォルダ（ローカル）',
    'label.outDir': '出力フォルダ（任意、既定：work/output）',
    'label.rulesDir': 'ルールフォルダ（任意、既定：work/quality）',
    'label.uploadFiles': 'スキャン対象をアップロード（.docx/.doc）',
    'placeholder.absPath': '絶対パスを入力',
    'placeholder.outDir': '絶対パス（空なら既定）',
    'placeholder.rulesDir': '絶対パス（空なら既定）',
    'placeholder.noFiles': '未選択',
    'hint.uploadExplain': 'アップロードしたファイルは既定の input に保存され、結果は既定の output に出力されます。上書き防止のためファイル名にタイムスタンプが付きます。',
    'section.summary': 'サマリー',
    'section.issues': '問題一覧',
    'section.llmConfig': 'モデル接続パラメータ',
    'stat.totalFiles': '総ファイル数',
    'stat.scannedFiles': 'スキャン済み',
    'stat.totalIssues': '問題数合計',
    'stat.scanStatus': '状態',
    'table.fileName': 'ファイル',
    'table.status': '状態',
    'table.startTime': '開始',
    'table.endTime': '終了',
    'table.duration': '時間',
    'table.ruleCount': 'ルール数',
    'table.issueCount': '問題数',
    'table.output': '出力',
    'empty.noNotifications': '通知なし',
    'empty.noData': 'データなし',
    'empty.noSubDirs': 'サブフォルダなし',
    'issue.filterFile': 'ファイル',
    'issue.pageInfo': '{page} / {total} ページ（{count}件）',
    'issue.page': 'ページ',
    'issue.section': '章',
    'issue.description': '内容',
    'issue.relatedStandard': '関連ルール',
    'issue.evidence': '抜粋',
    'issue.suggestion': '提案',
    'severity.high': '高',
    'severity.medium': '中',
    'severity.low': '低',
    'tag.failed': '失敗',
    'tag.completed': '完了',
    'tag.started': '開始',
    'tag.scanning': '実行中',
    'tag.reviewPending': '未処理',
    'tag.reviewAccepted': '承認済み',
    'tag.reviewRejected': '却下済み',
    'scan.status.running': '実行中',
    'scan.status.done': '完了',
    'scan.status.failed': '失敗',
    'scan.status.notStarted': '未開始',
    'msg.pickUploadFiles': 'アップロードするファイルを選択してください',
    'msg.fillReqDir': '要件フォルダを入力してください',
    'msg.startingScan': '開始しています...',
    'msg.startFailed': '開始に失敗しました',
    'msg.readDirFailedPrefix': 'フォルダの読み取りに失敗：',
    'msg.revisedWrittenPrefix': '修正版Word：',
    'msg.failPrefix': '失敗: ',
    'msg.runtimeConfigHeader': '現在のモデル接続パラメータ',
    'notif.newIssue': '新しい問題：{category}（{severity}）',
    'llm.url': 'LLM URL',
    'llm.model': 'モデル名',
    'llm.authMode': '認証方式',
    'llm.apiKey': 'API Key',
    'llm.timeoutSeconds': 'タイムアウト(秒)',
    'llm.ruleChunkSize': 'ルール分割数',
    'llm.configPath': '設定ファイル',
    'llm.loadError': '読み取り失敗',
    'time.h': '時間',
    'time.m': '分',
    'time.s': '秒',
  },
}

function t(key, vars) {
  const table = dict[lang.value] || dict.zh
  let s = String(table[key] ?? dict.zh[key] ?? key)
  if (vars && typeof vars === 'object') {
    for (const [k, v] of Object.entries(vars)) {
      s = s.split('{' + k + '}').join(String(v))
    }
  }
  return s
}

function loadLang() {
  try {
    const saved = localStorage.getItem('spec_qc_lang')
    if (saved === 'zh' || saved === 'en' || saved === 'ja') return saved
  } catch (e) {
  }
  const nav = String(navigator?.language || '').toLowerCase()
  if (nav.startsWith('ja')) return 'ja'
  if (nav.startsWith('zh')) return 'zh'
  return 'en'
}

watch(() => lang.value, (v) => {
  try {
    localStorage.setItem('spec_qc_lang', String(v || ''))
  } catch (e) {
  }
})

const reqDir = ref('')
const outDir = ref('')
const rulesDir = ref('')
const showConfig = ref(true)
const showProgress = ref(true)
const showLogs = ref(true)
const openMode = ref('local')

const reqPicker = ref(null)
const reqFiles = ref([])
const llmRuntime = ref({
  url: '',
  model: '',
  authMode: '',
  apiKeyMasked: '',
  timeoutSeconds: '',
  ruleChunkSize: '',
  configPath: '',
  error: '',
})

const jobId = ref('')
const statusText = ref('')
const progressFiles = ref([])
const progressData = ref(null)
const pollTimer = ref(null)

const issuesByFile = ref({})
const selectedFile = ref('__ALL__')
const notifications = ref([])
const notifOpen = ref(false)
const highlightKey = ref('')
const issueOpen = ref({})
const reviewBusy = ref({})
const issuePage = ref(1)
const issuePageSize = 10

const isScanning = computed(() => !!pollTimer.value)

const fileOptions = computed(() => {
  const names = (progressFiles.value || []).map((it) => String(it.file_name || '')).filter(Boolean)
  const unique = Array.from(new Set(names))
  unique.sort((a, b) => a.localeCompare(b))
  return unique
})

const summary = computed(() => {
  const p = progressData.value || {}
  const files = (p.files || progressFiles.value || [])
  const totalIssues = files.reduce((acc, it) => acc + (Number(it.issue_count) || 0), 0)
  return {
    totalFiles: Number(p.total_files || files.length || 0),
    scannedFiles: Number(p.scanned_files || files.filter((it) => isDoneStatus(String(it.status || '')) || isFailedStatus(String(it.status || ''))).length),
    totalIssues,
  }
})

const fileStatusMap = computed(() => {
  const map = {}
  for (const it of (progressFiles.value || [])) {
    const name = String(it?.file_name || '')
    if (!name) continue
    map[name] = String(it?.status || '')
  }
  return map
})

const scanStatusText = computed(() => {
  const p = progressData.value || {}
  const st = String(p.status || '')
  if (st === 'running') {
    const files = (p.files || progressFiles.value || [])
    const active = files.find((it) => {
      const s = String(it.status || '')
      return s && !isDoneStatus(s) && !isFailedStatus(s)
    })
    const detail = active ? String(active.status || '') : ''
    return detail || t('scan.status.running')
  }
  if (st === 'complete' || st === 'done') return t('scan.status.done')
  if (st === 'failed' || st === 'error') return t('scan.status.failed')
  if (isScanning.value) return t('scan.status.running')
  return t('scan.status.notStarted')
})

const displayedIssues = computed(() => {
  const all = selectedFile.value === '__ALL__'
  const byFile = issuesByFile.value || {}
  const filterOutRejected = (arr) => (arr || []).filter((x) => String(x?.review_status || '').toLowerCase() !== 'rejected')
  if (!all) {
    const arr = filterOutRejected(byFile[selectedFile.value] || [])
    return arr.map((it, idx) => ({ ...it, __file: selectedFile.value, __key: selectedFile.value + ':' + (it.seq || idx) }))
  }
  const rows = []
  for (const [file, arr] of Object.entries(byFile)) {
    const filtered = filterOutRejected(arr || [])
    for (let i = 0; i < filtered.length; i++) {
      const it = filtered[i]
      rows.push({ ...it, __file: file, __key: file + ':' + (it.seq || i) })
    }
  }
  return rows
})

const issueTotalPages = computed(() => {
  const total = displayedIssues.value.length
  return Math.max(1, Math.ceil(total / issuePageSize))
})

const pagedIssues = computed(() => {
  const total = displayedIssues.value.length
  if (!total) return []
  const maxPage = issueTotalPages.value
  const page = Math.max(1, Math.min(issuePage.value, maxPage))
  const start = (page - 1) * issuePageSize
  return displayedIssues.value.slice(start, start + issuePageSize)
})

watch(() => selectedFile.value, () => {
  issuePage.value = 1
})

watch(() => displayedIssues.value.length, () => {
  issuePage.value = 1
})

watch(() => issueTotalPages.value, () => {
  if (issuePage.value > issueTotalPages.value) {
    issuePage.value = issueTotalPages.value
  }
  if (issuePage.value < 1) {
    issuePage.value = 1
  }
})

function prevIssuePage() {
  if (issuePage.value > 1) {
    issuePage.value -= 1
  }
}

function nextIssuePage() {
  if (issuePage.value < issueTotalPages.value) {
    issuePage.value += 1
  }
}

function collapseAllIssues() {
  const next = { ...(issueOpen.value || {}) }
  for (const row of (displayedIssues.value || [])) {
    const key = String(row?.__key || '').trim()
    if (!key) continue
    next[key] = false
  }
  issueOpen.value = next
}

async function getJson(url) {
  const resp = await fetch(url)
  return await resp.json()
}

async function postJson(url, obj) {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(obj),
  })
  return await resp.json()
}

async function postForm(url, formData) {
  const resp = await fetch(url, { method: 'POST', body: formData })
  return await resp.json()
}

async function loadLlmConfig() {
  try {
    const data = await getJson('/api/llm_config')
    llmRuntime.value = {
      url: String(data?.url || ''),
      model: String(data?.model || ''),
      authMode: String(data?.auth_mode || ''),
      apiKeyMasked: String(data?.api_key_masked || ''),
      timeoutSeconds: String(data?.timeout_seconds || ''),
      ruleChunkSize: String(data?.rule_chunk_size || ''),
      configPath: String(data?.config_path || ''),
      error: '',
    }
  } catch (e) {
    llmRuntime.value = {
      url: '',
      model: '',
      authMode: '',
      apiKeyMasked: '',
      timeoutSeconds: '',
      ruleChunkSize: '',
      configPath: '',
      error: String(e),
    }
  }
}

function llmRuntimeLines() {
  const info = llmRuntime.value || {}
  const lines = [t('msg.runtimeConfigHeader')]
  if (info.url) lines.push(t('llm.url') + ': ' + info.url)
  if (info.model) lines.push(t('llm.model') + ': ' + info.model)
  if (info.authMode) lines.push(t('llm.authMode') + ': ' + info.authMode)
  if (info.apiKeyMasked) lines.push(t('llm.apiKey') + ': ' + info.apiKeyMasked)
  if (info.timeoutSeconds) lines.push(t('llm.timeoutSeconds') + ': ' + info.timeoutSeconds)
  if (info.ruleChunkSize) lines.push(t('llm.ruleChunkSize') + ': ' + info.ruleChunkSize)
  if (info.configPath) lines.push(t('llm.configPath') + ': ' + info.configPath)
  if (info.error) lines.push(t('llm.loadError') + ': ' + info.error)
  return lines
}

function formatLocalTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  const loc = lang.value === 'ja' ? 'ja-JP' : (lang.value === 'en' ? 'en-US' : 'zh-CN')
  return d.toLocaleString(loc)
}

function formatDuration(ms) {
  const n = Number(ms)
  if (!Number.isFinite(n) || n < 0) return ''
  const totalSec = Math.floor(n / 1000)
  const hh = Math.floor(totalSec / 3600)
  const mm = Math.floor((totalSec % 3600) / 60)
  const ss = totalSec % 60
  const parts = []
  if (hh) parts.push(hh + t('time.h'))
  if (mm) parts.push(mm + t('time.m'))
  parts.push(ss + t('time.s'))
  return lang.value === 'en' ? parts.join(' ') : parts.join('')
}

function isFailedStatus(v) {
  return v.includes('失败') || v.includes('Failed') || v.includes('Error') || v.includes('エラー')
}

function isDoneStatus(v) {
  return v.includes('完成') || v.includes('Done') || v.includes('Complete') || v.includes('完了')
}

function isStartStatus(v) {
  return v.includes('开始') || v.includes('Start') || v.includes('開始')
}

function statusTagClass(s) {
  const v = String(s || '')
  if (isFailedStatus(v)) return 'tag-red'
  if (isDoneStatus(v)) return 'tag-green'
  if (isStartStatus(v)) return 'tag-blue'
  return 'tag-gold'
}

function statusTagText(s) {
  const v = String(s || '')
  if (isFailedStatus(v)) return t('tag.failed')
  if (isDoneStatus(v)) return t('tag.completed')
  if (isStartStatus(v)) return t('tag.started')
  return t('tag.scanning')
}

function failureReason(s) {
  const v = String(s || '').trim()
  if (!v) return ''
  if (!isFailedStatus(v)) return ''
  const seps = [':', '：', '-', '—']
  for (const sep of seps) {
    const idx = v.indexOf(sep)
    if (idx > 0) {
      const left = v.slice(0, idx).toLowerCase()
      const right = v.slice(idx + 1).trim()
      if (!right) continue
      if (left.includes('失败') || left.includes('failed') || left.includes('error') || left.includes('失敗') || left.includes('エラー')) {
        return right
      }
    }
  }
  return ''
}

function reviewTagClass(s) {
  const v = String(s || '').toLowerCase()
  if (v === 'accepted') return 'tag-green'
  if (v === 'rejected') return 'tag-red'
  return 'tag-blue'
}

function reviewTagText(s) {
  const v = String(s || '').toLowerCase()
  if (v === 'accepted') return t('tag.reviewAccepted')
  if (v === 'rejected') return t('tag.reviewRejected')
  return t('tag.reviewPending')
}

function normalizeSeverity(raw) {
  const v = String(raw || '').trim().toLowerCase()
  if (!v) return 'low'
  if (v === '高' || v === 'high' || v === 'h') return 'high'
  if (v === '中' || v === 'medium' || v === 'm') return 'medium'
  if (v === '低' || v === 'low' || v === 'l') return 'low'
  if (v.includes('high')) return 'high'
  if (v.includes('medium')) return 'medium'
  if (v.includes('low')) return 'low'
  return 'low'
}

function severityTagClass(raw) {
  const lvl = normalizeSeverity(raw)
  if (lvl === 'high') return 'tag-red'
  if (lvl === 'medium') return 'tag-gold'
  return 'tag-blue'
}

function severityText(raw) {
  const lvl = normalizeSeverity(raw)
  if (lvl === 'high') return t('severity.high')
  if (lvl === 'medium') return t('severity.medium')
  return t('severity.low')
}

function isFileReviewable(fileName) {
  const name = String(fileName || '').trim()
  if (!name) return false
  const st = String(fileStatusMap.value?.[name] || '')
  return isDoneStatus(st) || isFailedStatus(st)
}

async function decide(row, action) {
  const file = String(row?.__file || selectedFile.value || '')
  const seq = String(row?.seq || '').trim()
  if (!file || !seq || !jobId.value) return
  if (!isFileReviewable(file)) return
  const key = String(row?.__key || (file + ':' + seq)).trim()
  const reqId = Date.now() + '-' + Math.random().toString(16).slice(2)
  reviewBusy.value = { ...(reviewBusy.value || {}), [key]: true }
  appendStatusLog(`[ui] click ${action} req_id=${reqId} file=${file} seq=${seq}`)
  sendClientLog({ req_id: reqId, stage: 'click', action, file, seq })
  try {
    appendStatusLog(`[ui] request ${action} req_id=${reqId}`)
    sendClientLog({ req_id: reqId, stage: 'request_start', action, file, seq })
    const data = await postJson('/api/review/decision', { job_id: jobId.value, file_name: file, seq, action, req_id: reqId, lang: lang.value })
    appendStatusLog(`[ui] response ${action} req_id=${reqId} ok=${!data?.error}`)
    sendClientLog({ req_id: reqId, stage: 'response', action, file, seq, ok: data?.error ? 'false' : 'true', error: String(data?.error || '') })
    if (data?.error) {
      statusText.value = String(data.error)
      return
    }
    const issues = Array.isArray(data?.issues) ? data.issues : []
    issuesByFile.value = { ...(issuesByFile.value || {}), [file]: issues }
    const revised = String(data?.revised_path || '').trim()
    if (revised) {
      statusText.value = t('msg.revisedWrittenPrefix') + revised
    }
  } catch (e) {
    statusText.value = String(e)
    appendStatusLog(`[ui] error ${action} req_id=${reqId} err=${String(e)}`)
    sendClientLog({ req_id: reqId, stage: 'error', action, file, seq, error: String(e) })
  } finally {
    const next = { ...(reviewBusy.value || {}) }
    delete next[key]
    reviewBusy.value = next
  }
}

const knownIssueKeys = new Set()

function pushNotifications(nextIssues) {
  const list = []
  for (const [file, arr] of Object.entries(nextIssues || {})) {
    for (const it of (arr || [])) {
      const seq = String(it?.seq || '').trim()
      if (!seq) continue
      const key = file + ':' + seq
      if (knownIssueKeys.has(key)) continue
      knownIssueKeys.add(key)
      const category = String(it?.category || '')
      const severity = severityText(it?.severity)
      const title = t('notif.newIssue', { category, severity })
      list.push({ id: key, file, seq, title })
    }
  }
  if (list.length) {
    notifications.value = [...list, ...(notifications.value || [])].slice(0, 50)
  }
}

function toggleNotif() {
  notifOpen.value = !notifOpen.value
}

async function openNotif(n) {
  const file = String(n?.file || '')
  const seq = String(n?.seq || '')
  if (!file || !seq) return
  notifOpen.value = false
  notifications.value = (notifications.value || []).filter((x) => x && x.id !== n.id)
  selectedFile.value = file
  await selectFile(file)
  highlightKey.value = file + ':' + seq
  setTimeout(() => {
    const el = document.querySelector('[data-key="' + highlightKey.value.replaceAll('"', '') + '"]')
    if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, 50)
}

function escapeHtml(s) {
  return String(s || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function renderEvidenceHtml(row) {
  const paragraphRaw = String(row?.evidence_paragraph || row?.evidence_excerpt || '')
  const excerptRaw = String(row?.evidence_excerpt || '')
  const paragraph = escapeHtml(paragraphRaw)
  const excerpt = escapeHtml(excerptRaw)
  const base = (!excerpt || !paragraph || !paragraph.includes(excerpt))
    ? paragraph
    : paragraph.split(excerpt).join('<span class="hl">' + excerpt + '</span>')
  return base.replaceAll('\n', '<br>')
}

function sanitizeHtmlAllowlist(html) {
  const raw = String(html || '')
  if (!raw.trim()) return ''
  const removedScripts = raw.replace(/<\s*script[^>]*>[\s\S]*?<\s*\/\s*script\s*>/gi, '')
  const allow = new Set(['table', 'thead', 'tbody', 'tr', 'th', 'td', 'p', 'ul', 'ol', 'li', 'br', 'strong', 'b', 'code', 'pre'])
  return removedScripts.replace(/<\/?\s*([a-zA-Z0-9]+)(\s+[^>]*)?\s*>/g, (m, tagName) => {
    const t = String(tagName || '').toLowerCase()
    if (!allow.has(t)) return ''
    const isClose = m.trim().startsWith('</')
    if (t === 'br') return '<br>'
    return isClose ? `</${t}>` : `<${t}>`
  })
}

function renderSuggestionHtml(row) {
  const html = String(row?.suggestion_html || '')
  if (html.trim()) return sanitizeHtmlAllowlist(html)
  const text = escapeHtml(String(row?.suggestion || ''))
  return text.replaceAll('\n', '<br>')
}

function nowTs() {
  return new Date().toLocaleString()
}

function appendStatusLog(line) {
  const t = `${nowTs()} ${String(line || '')}`.trim()
  const prev = String(statusText.value || '')
  statusText.value = (prev ? (prev + '\n') : '') + t
}

async function sendClientLog(obj) {
  try {
    await postJson('/api/client/log', obj || {})
  } catch (e) {
  }
}

function isReviewBusy(row) {
  const key = String(row?.__key || '').trim()
  if (!key) return false
  return !!(reviewBusy.value || {})[key]
}

function isIssueOpen(row) {
  const key = String(row?.__key || '').trim()
  if (!key) return true
  const v = (issueOpen.value || {})[key]
  return v === undefined ? true : !!v
}

function toggleIssue(row) {
  const key = String(row?.__key || '').trim()
  if (!key) return
  const next = { ...(issueOpen.value || {}) }
  next[key] = !isIssueOpen(row)
  issueOpen.value = next
}

const uploadSummary = computed(() => {
  const arr = reqFiles.value || []
  if (!arr.length) return ''
  if (arr.length === 1) return String(arr[0]?.name || '')
  if (lang.value === 'en') return String(arr.length) + ' files'
  if (lang.value === 'ja') return String(arr.length) + ' ファイル'
  return String(arr.length) + ' 个文件'
})

async function loadCache() {
  try {
    const data = await getJson('/api/cache')
    reqDir.value = data?.req_dir || 'work/input'
    outDir.value = data?.out_dir || 'work/output'
    rulesDir.value = data?.rules_dir || 'work/quality'
  } catch (e) {
    statusText.value = String(e)
    if (!reqDir.value) reqDir.value = 'work/input'
    if (!outDir.value) outDir.value = 'work/output'
    if (!rulesDir.value) rulesDir.value = 'work/quality'
  }
}

async function pickDir(field) {
  const key = String(field || '').trim()
  if (!key) return
  let initial = ''
  if (key === 'reqDir') initial = reqDir.value
  else if (key === 'outDir') initial = outDir.value
  else if (key === 'rulesDir') initial = rulesDir.value
  try {
    const data = await getJson('/api/pick_dir?initial=' + encodeURIComponent(String(initial || '')))
    const p = String(data?.path || '')
    if (!p) {
      if (data?.error) statusText.value = String(data.error)
      return
    }
    if (key === 'reqDir') reqDir.value = p
    else if (key === 'outDir') outDir.value = p
    else if (key === 'rulesDir') rulesDir.value = p
  } catch (e) {
    statusText.value = String(e)
  }
}

function pickReqFiles() {
  if (reqPicker.value) reqPicker.value.click()
}

function clearReqFiles() {
  reqFiles.value = []
  if (reqPicker.value) {
    try {
      reqPicker.value.value = ''
    } catch (e) {
    }
  }
}

function onReqFilesChange(e) {
  const files = Array.from(e?.target?.files || [])
  const filtered = files.filter((f) => {
    const n = String(f.name || '').toLowerCase()
    return n.endsWith('.docx') || n.endsWith('.doc')
  })
  reqFiles.value = filtered
}

async function startScan() {
  if (openMode.value === 'upload') {
    if (!reqFiles.value.length) {
      statusText.value = t('msg.pickUploadFiles')
      return
    }
  } else {
    if (!reqDir.value.trim()) {
      statusText.value = t('msg.fillReqDir')
      return
    }
  }
  showConfig.value = false
  statusText.value = [t('msg.startingScan'), ...llmRuntimeLines()].join('\n')
  progressFiles.value = []
  progressData.value = null
  selectedFile.value = '__ALL__'
  issuesByFile.value = {}
  notifications.value = []
  notifOpen.value = false
  highlightKey.value = ''
  knownIssueKeys.clear()
  try {
    let data
    if (openMode.value === 'upload') {
      const fd = new FormData()
      for (const f of reqFiles.value) fd.append('req_files', f, f.name)
      fd.append('lang', lang.value)
      data = await postForm('/api/scan_upload', fd)
    } else {
      data = await postJson('/api/scan', { req_dir: reqDir.value, out_dir: outDir.value, rules_dir: rulesDir.value, lang: lang.value })
    }
    jobId.value = data?.job_id || ''
    if (!jobId.value) {
      statusText.value = data?.error || t('msg.startFailed')
      return
    }
    pollTimer.value = setInterval(pollStatus, 1000)
    await pollStatus()
  } catch (e) {
    statusText.value = String(e)
  }
}

async function pollStatus() {
  if (!jobId.value) return
  try {
    const data = await getJson('/api/status/' + encodeURIComponent(jobId.value))
    const p = data?.progress
    progressData.value = p || null
    progressFiles.value = (p?.files || []).slice().sort((a, b) => String(a.file_name || '').localeCompare(String(b.file_name || '')))
    const nextIssues = {}
    for (const it of (p?.files || [])) {
      const name = String(it?.file_name || '')
      if (!name) continue
      const issues = Array.isArray(it?.issues) ? it.issues : null
      if (issues) nextIssues[name] = issues
    }
    if (Object.keys(nextIssues).length) {
      issuesByFile.value = { ...(issuesByFile.value || {}), ...nextIssues }
      pushNotifications(nextIssues)
    }
    const logs = Array.isArray(data?.logs) ? data.logs : []
    const base = (data?.error ? (t('msg.failPrefix') + data.error) : (data?.message || ''))
    statusText.value = logs.length ? (logs.join('\n') + (base ? ('\n' + base) : '')) : base
    if (data?.status === 'done' || data?.status === 'error') {
      if (pollTimer.value) {
        clearInterval(pollTimer.value)
        pollTimer.value = null
      }
    }
  } catch (e) {
    statusText.value = String(e)
    if (pollTimer.value) {
      clearInterval(pollTimer.value)
      pollTimer.value = null
    }
  }
}

function download(fileName) {
  if (!jobId.value) return
  const url = '/api/download?job_id=' + encodeURIComponent(jobId.value) + '&file_name=' + encodeURIComponent(fileName) + '&lang=' + encodeURIComponent(lang.value)
  window.location.href = url
}

async function selectFile(fileName) {
  const name = String(fileName || '')
  if (!name || !jobId.value) return
  selectedFile.value = name
  if ((issuesByFile.value || {})[name]) {
    return
  }
  try {
    const data = await getJson('/api/issues?job_id=' + encodeURIComponent(jobId.value) + '&file_name=' + encodeURIComponent(name))
    issuesByFile.value = { ...(issuesByFile.value || {}), [name]: (data?.issues || []) }
  } catch (e) {
    issuesByFile.value = { ...(issuesByFile.value || {}), [name]: [] }
  }
}

function onSelectedFileChange() {
  const v = selectedFile.value
  if (v && v !== '__ALL__') {
    selectFile(v)
  }
}

onMounted(async () => {
  await loadCache()
  await loadLlmConfig()
})

onBeforeUnmount(() => {
  if (pollTimer.value) clearInterval(pollTimer.value)
})
</script>
