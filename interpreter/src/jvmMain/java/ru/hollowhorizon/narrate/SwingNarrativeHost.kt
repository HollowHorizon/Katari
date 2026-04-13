package ru.hollowhorizon.narrate

import com.sunnychung.lib.multiplatform.kotlite.narrative.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.EntityValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueSnapshot
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

class SwingNarrativeHost : NarrativeHost {

    private val frame = JFrame("Narrative Test")
    private val logArea = JTextArea()
    private val choicesPanel = JPanel()
    private val controlsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    private val continueButton = JButton("Продолжить")

    init {
        SwingUtilities.invokeAndWait {
            logArea.isEditable = false
            logArea.lineWrap = true
            logArea.wrapStyleWord = true
            logArea.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            choicesPanel.layout = BoxLayout(choicesPanel, BoxLayout.Y_AXIS)
            choicesPanel.border = BorderFactory.createTitledBorder("Выбор")

            continueButton.isEnabled = false

            controlsPanel.add(continueButton)

            val root = JPanel(BorderLayout(8, 8))
            root.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            root.add(JScrollPane(logArea), BorderLayout.CENTER)
            root.add(choicesPanel, BorderLayout.SOUTH)
            root.add(controlsPanel, BorderLayout.NORTH)

            frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            frame.contentPane = root
            frame.minimumSize = Dimension(700, 500)
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
        }
    }

    override fun narrate(text: String, resume: () -> Unit) {
        showText(text)
        showContinue(resume)
    }

    override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) {
        val speakerName = when (speaker) {
            is EntityValueSnapshot -> speaker.id
            else -> "unknown"
        }
        showText("$speakerName: $text")
        showContinue(resume)
    }

    override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
        SwingUtilities.invokeLater {
            continueButton.isEnabled = false
            continueButton.actionListeners.forEach { continueButton.removeActionListener(it) }

            choicesPanel.removeAll()

            options.forEach { option ->
                val button = JButton(option.text)
                button.alignmentX = JPanel.LEFT_ALIGNMENT
                button.maximumSize = Dimension(Int.MAX_VALUE, 32)

                button.addActionListener {
                    disableAllChoiceButtons()
                    appendLog("> ${option.text}")
                    clearChoices()
                    resume(option.text)
                }

                choicesPanel.add(button)
            }

            choicesPanel.revalidate()
            choicesPanel.repaint()
        }
    }

    override fun readLine(resume: (String) -> Unit) {
        SwingUtilities.invokeLater {
            val value = JOptionPane.showInputDialog(frame, "Введите ответ") ?: ""
            appendLog("> $value")
            resume(value)
        }
    }

    private fun showText(text: String) {
        SwingUtilities.invokeLater {
            appendLog(text)
            clearChoices()
        }
    }

    private fun showContinue(resume: () -> Unit) {
        SwingUtilities.invokeLater {
            continueButton.isEnabled = true
            continueButton.actionListeners.forEach { continueButton.removeActionListener(it) }
            continueButton.addActionListener {
                continueButton.isEnabled = false
                resume()
            }
        }
    }

    private fun appendLog(text: String) {
        logArea.append(text)
        logArea.append("\n\n")
        logArea.caretPosition = logArea.document.length
    }

    private fun clearChoices() {
        choicesPanel.removeAll()
        choicesPanel.revalidate()
        choicesPanel.repaint()
    }

    private fun disableAllChoiceButtons() {
        for (i in 0 until choicesPanel.componentCount) {
            choicesPanel.getComponent(i).isEnabled = false
        }
    }
}
