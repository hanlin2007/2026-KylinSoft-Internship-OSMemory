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
import com.example.osmemory.core.model.ModelConfig
import com.example.osmemory.data.MemoryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型设置页（阶段 2）：修改 Base URL / Model / API Key，测试连接，保存即热插拔。
 *
 * 降级可观测：测试结果直接展示"成功/失败 + 具体原因"（网络/HTTP/解析），
 * 保存后 ModelManager.reset() 重建通道，业务代码零改动。
 */
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
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)

        // 回填当前配置
        etBaseUrl.setText(ModelConfig.baseUrl(context))
        etModel.setText(ModelConfig.model(context))
        etApiKey.setText(ModelConfig.apiKey(context))

        view.findViewById<TextView>(R.id.btnTest).setOnClickListener {
            testConnection(etBaseUrl, etModel, etApiKey, view)
        }
        view.findViewById<TextView>(R.id.btnSave).setOnClickListener {
            save(etBaseUrl, etModel, etApiKey, view)
        }
    }

    private fun testConnection(
        etBaseUrl: EditText, etModel: EditText, etApiKey: EditText, root: View
    ) {
        val baseUrl = etBaseUrl.text?.toString()?.trim().orEmpty()
        val model = etModel.text?.toString()?.trim().orEmpty()
        val apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            toast("Base URL / 模型 / API Key 不能为空")
            return
        }
        val tvResult = root.findViewById<TextView>(R.id.tvTestResult)
        tvResult.isVisible = true
        tvResult.text = "正在测试连接…（${baseUrl.trimEnd('/')}/chat/completions）"
        tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.semantic_public))

        viewLifecycleOwner.lifecycleScope.launch {
            // 先临时保存，让 provider 用新配置测试；失败/成功都展示原因
            withContext(Dispatchers.IO) {
                MemoryService.repo(requireContext()).saveModelConfig(baseUrl, model, apiKey)
            }
            val record = withContext(Dispatchers.IO) {
                MemoryService.repo(requireContext()).testModelConnection()
            }
            tvResult.text = buildString {
                append("测试结果：")
                if (record.ok) append("成功（${record.durationMs}ms）· ${record.channel}")
                else append("失败 → ${record.message}")
            }
            tvResult.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (record.ok) R.color.semantic_normal else R.color.semantic_sensitive
                )
            )
        }
    }

    private fun save(etBaseUrl: EditText, etModel: EditText, etApiKey: EditText, root: View) {
        val baseUrl = etBaseUrl.text?.toString()?.trim().orEmpty()
        val model = etModel.text?.toString()?.trim().orEmpty()
        val apiKey = etApiKey.text?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            toast("Base URL / 模型 / API Key 不能为空")
            return
        }
        MemoryService.repo(requireContext()).saveModelConfig(baseUrl, model, apiKey)
        toast("已保存并热插拔模型通道：$model")
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun newInstance() = ModelSettingsFragment()
    }
}
