package com.example.osmemory.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.osmemory.R
import com.example.osmemory.core.model.LocalModelSpec
import com.example.osmemory.core.model.LocalModelStore
import com.example.osmemory.core.model.ModelConfig
import com.example.osmemory.data.MemoryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 云端与端侧模型并列配置、并列连通测试。 */
class ModelSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_model_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val etBaseUrl = view.findViewById<EditText>(R.id.etBaseUrl)
        val etModel = view.findViewById<EditText>(R.id.etModel)
        val etLocalModel = view.findViewById<EditText>(R.id.etLocalModel)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)

        etBaseUrl.setText(ModelConfig.baseUrl(context))
        etModel.setText(ModelConfig.model(context))
        etLocalModel.setText(LocalModelSpec.ID)
        etApiKey.setText(ModelConfig.apiKey(context))
        renderLocalStatus(view)

        view.findViewById<TextView>(R.id.btnTestCloud).setOnClickListener {
            testCloud(etBaseUrl, etModel, etApiKey, view)
        }
        view.findViewById<TextView>(R.id.btnTestLocal).setOnClickListener {
            testLocal(view)
        }
        view.findViewById<TextView>(R.id.btnSave).setOnClickListener {
            save(etBaseUrl, etModel, etLocalModel, etApiKey)
        }

        // 首次启动自动下载进度（后台触发）；下载完成自动刷新状态行
        viewLifecycleOwner.lifecycleScope.launch {
            MemoryService.repo(requireContext()).observeLocalModelDownload().collect { progress ->
                if (progress == null) {
                    if (isAdded) renderLocalStatus(view)
                } else {
                    val percent = if (progress.total > 0L) {
                        (progress.downloaded * 100L / progress.total).coerceIn(0L, 100L)
                    } else 0L
                    view.findViewById<TextView>(R.id.tvLocalTestResult).apply {
                        isVisible = true
                        text = "正在后台自动下载端侧模型…$percent%（下载完成后即可离线使用）"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.semantic_public))
                    }
                }
            }
        }
    }

    private fun testCloud(
        etBaseUrl: EditText,
        etModel: EditText,
        etApiKey: EditText,
        root: View
    ) {
        val baseUrl = etBaseUrl.text?.toString()?.trim().orEmpty()
        val model = etModel.text?.toString()?.trim().orEmpty()
        val apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            toast("Base URL / 云端模型 / API Key 不能为空")
            return
        }
        val resultView = root.findViewById<TextView>(R.id.tvCloudTestResult)
        resultView.showInfo("正在测试云端模型…")

        viewLifecycleOwner.lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) {
                MemoryService.repo(requireContext()).testCloudConnection(baseUrl, model, apiKey)
            }
            resultView.showRecord(record.ok, record.durationMs, record.channel, record.message)
        }
    }

    private fun testLocal(root: View) {
        val button = root.findViewById<TextView>(R.id.btnTestLocal)
        val resultView = root.findViewById<TextView>(R.id.tvLocalTestResult)
        button.isEnabled = false
        resultView.showInfo("正在准备端侧模型…首次使用会下载约 469 MB")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LocalModelStore.ensureReady(requireContext()) { progress ->
                        val percent = if (progress.total > 0L) {
                            (progress.downloaded * 100L / progress.total).coerceIn(0L, 100L)
                        } else 0L
                        resultView.post {
                            if (isAdded && view === root) {
                                resultView.showInfo("正在下载并校验端侧模型…$percent%")
                            }
                        }
                    }
                }
                if (!isAdded || view !== root) return@launch
                renderLocalStatus(root)
                resultView.showInfo("模型已校验，正在由 llama.cpp 加载并生成…")
                val record = withContext(Dispatchers.IO) {
                    MemoryService.repo(requireContext()).testLocalConnection()
                }
                resultView.showRecord(record.ok, record.durationMs, record.channel, record.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isAdded && view === root) {
                    resultView.showRecord(
                        ok = false,
                        durationMs = 0L,
                        channel = "端侧小模型",
                        message = error.message ?: error.javaClass.simpleName
                    )
                }
            } finally {
                if (isAdded && view === root) {
                    button.isEnabled = true
                    renderLocalStatus(root)
                }
            }
        }
    }

    private fun renderLocalStatus(root: View) {
        val status = LocalModelStore.status(requireContext())
        root.findViewById<TextView>(R.id.tvLocalModelStatus).apply {
            text = "${LocalModelSpec.DISPLAY_NAME} · ${status.message}"
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (status.ready) R.color.semantic_normal else R.color.semantic_public
                )
            )
        }
    }

    private fun save(
        etBaseUrl: EditText,
        etModel: EditText,
        etLocalModel: EditText,
        etApiKey: EditText
    ) {
        val baseUrl = etBaseUrl.text?.toString()?.trim().orEmpty()
        val model = etModel.text?.toString()?.trim().orEmpty()
        val localModel = etLocalModel.text?.toString()?.trim().orEmpty()
        val apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            toast("Base URL / 云端模型 / API Key 不能为空")
            return
        }
        MemoryService.repo(requireContext()).saveModelConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            localModel = localModel.ifBlank { LocalModelSpec.ID }
        )
        toast("模型配置已保存：在线走云端，离线走端侧 Qwen")
    }

    private fun TextView.showInfo(message: String) {
        isVisible = true
        text = message
        setTextColor(ContextCompat.getColor(requireContext(), R.color.semantic_public))
    }

    private fun TextView.showRecord(
        ok: Boolean,
        durationMs: Long,
        channel: String,
        message: String
    ) {
        isVisible = true
        text = if (ok) {
            "测试成功（${durationMs}ms）· $channel"
        } else {
            "测试失败 · $message"
        }
        setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (ok) R.color.semantic_normal else R.color.semantic_sensitive
            )
        )
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun newInstance() = ModelSettingsFragment()
    }
}
