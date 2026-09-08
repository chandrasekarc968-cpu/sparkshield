package com.sparkshield.android.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sparkshield.android.R
import com.sparkshield.android.data.entity.TamperEventEntity
import com.sparkshield.android.databinding.ItemTamperEventBinding
import com.sparkshield.android.inference.ClassLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : ListAdapter<TamperEventEntity, HistoryAdapter.ViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTamperEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTamperEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(event: TamperEventEntity) {
            with(binding) {
                tvClassName.text = event.className
                tvTimestamp.text = dateFormat.format(Date(event.timestampMs))
                tvConfidence.text = "${(event.confidence * 100).toInt()}%"
                
                val riskText: String
                val riskColor: Int
                
                when {
                    event.confidence >= 0.95f -> {
                        riskText = "CRITICAL"
                        riskColor = R.color.color_emp
                    }
                    event.confidence >= 0.85f -> {
                        riskText = "HIGH RISK"
                        riskColor = R.color.color_emp
                    }
                    else -> {
                        riskText = "MEDIUM RISK"
                        riskColor = R.color.color_optical
                    }
                }
                
                tvRiskLevel.text = riskText
                tvRiskLevel.setTextColor(ContextCompat.getColor(itemView.context, riskColor))
                vIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, riskColor))

                // Adjust indicator color based on class if needed
                val classLabels = ClassLabels.values().find { it.name == event.className }
                val classColor = when (classLabels) {
                    ClassLabels.EMP -> R.color.color_emp
                    ClassLabels.OPTICAL -> R.color.color_optical
                    ClassLabels.SURGE -> R.color.color_surge
                    else -> R.color.color_normal
                }
                vIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, classColor))
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<TamperEventEntity>() {
        override fun areItemsTheSame(oldItem: TamperEventEntity, newItem: TamperEventEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TamperEventEntity, newItem: TamperEventEntity): Boolean {
            return oldItem == newItem
        }
    }
}
