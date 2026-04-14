package ru.hollowhorizon.narrate

import com.sunnychung.lib.multiplatform.kotlite.narrative.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.EntityValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.TextValueSnapshot
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

class SwingNarrativeHost : NarrativeHost {

    private val frame = JFrame("Narrative Test")
    private val logArea = JTextArea()
    private val choicesPanel = JPanel()
    private val controlsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    private val continueButton = JButton("Continue")
    private val saveJsonButton = JButton("Save JSON")
    private val loadJsonButton = JButton("Load JSON")
    private val saveCborButton = JButton("Save CBOR")
    private val loadCborButton = JButton("Load CBOR")
    private val importHistoryButton = JButton("Import History")
    private val statusLabel = JLabel("Idle")

    init {
        SwingUtilities.invokeAndWait {
            logArea.isEditable = false
            logArea.lineWrap = true
            logArea.wrapStyleWord = true
            logArea.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            choicesPanel.layout = BoxLayout(choicesPanel, BoxLayout.Y_AXIS)
            choicesPanel.border = BorderFactory.createTitledBorder("Choices")

            continueButton.isEnabled = false

            controlsPanel.add(continueButton)
            controlsPanel.add(saveJsonButton)
            controlsPanel.add(loadJsonButton)
            controlsPanel.add(saveCborButton)
            controlsPanel.add(loadCborButton)
            controlsPanel.add(importHistoryButton)
            controlsPanel.add(statusLabel)

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

    fun bindActions(
        onSaveJson: (File) -> Unit,
        onLoadJson: (File) -> Unit,
        onSaveCbor: (File) -> Unit,
        onLoadCbor: (File) -> Unit,
        onImportHistory: (File) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            configureFileAction(saveJsonButton, "Save JSON Snapshot", "Save", "json", "JSON files", false, onSaveJson)
            configureFileAction(loadJsonButton, "Load JSON Snapshot", "Load", "json", "JSON files", true, onLoadJson)
            configureFileAction(saveCborButton, "Save CBOR Snapshot", "Save", "cbor", "CBOR files", false, onSaveCbor)
            configureFileAction(loadCborButton, "Load CBOR Snapshot", "Load", "cbor", "CBOR files", true, onLoadCbor)
            configureFileAction(importHistoryButton, "Import History", "Import", "txt", "Text files", true, onImportHistory)
        }
    }

    fun clearTranscript() {
        SwingUtilities.invokeLater {
            logArea.text = ""
            clearChoices()
            continueButton.isEnabled = false
            continueButton.actionListeners.forEach { continueButton.removeActionListener(it) }
        }
    }

    fun replaceTranscript(text: String) {
        SwingUtilities.invokeLater {
            logArea.text = text
            if (logArea.text.isNotEmpty() && !logArea.text.endsWith("\n")) {
                logArea.append("\n")
            }
            logArea.caretPosition = logArea.document.length
        }
    }

    fun setStatus(text: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = text
        }
    }

    fun showError(message: String, error: Throwable) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                frame,
                "$message\n${error.message ?: error::class.simpleName}",
                "Narrative Error",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    override fun narrate(text: String, resume: () -> Unit) {
        showText(text)
        showContinue(resume)
    }

    override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) {
        val speakerName = when (speaker) {
            is EntityValueSnapshot -> speaker.id
            is TextValueSnapshot -> speaker.value
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
                button.isEnabled = option.enabled
                if (option.enabled) {
                    button.addActionListener {
                        disableAllChoiceButtons()
                        appendLog("> ${option.text}")
                        clearChoices()
                        resume(option.id)
                    }
                }
                choicesPanel.add(button)
            }

            choicesPanel.revalidate()
            choicesPanel.repaint()
        }
    }

    override fun readLine(question: String, resume: (String) -> Unit) {
        SwingUtilities.invokeLater {
            val value = JOptionPane.showInputDialog(frame, question) ?: ""
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

    private fun configureFileAction(
        button: JButton,
        dialogTitle: String,
        approveText: String,
        extension: String,
        description: String,
        openDialog: Boolean,
        action: (File) -> Unit,
    ) {
        button.actionListeners.forEach { button.removeActionListener(it) }
        button.addActionListener {
            val chooser = JFileChooser().apply {
                this.name = dialogTitle
                dialogType = if (openDialog) JFileChooser.OPEN_DIALOG else JFileChooser.SAVE_DIALOG
                fileFilter = FileNameExtensionFilter(description, extension)
                selectedFile = File("narrative-save.$extension")
                approveButtonText = approveText
            }
            val result = if (openDialog) chooser.showOpenDialog(frame) else chooser.showSaveDialog(frame)
            if (result == JFileChooser.APPROVE_OPTION) {
                action(chooser.selectedFile.ensureExtension(extension, openDialog))
            }
        }
    }
}

private fun File.ensureExtension(extension: String, preserveOriginalForOpen: Boolean): File {
    if (preserveOriginalForOpen) {
        return this
    }
    return if (name.endsWith(".$extension", ignoreCase = true)) {
        this
    } else {
        File(parentFile ?: File("."), "$name.$extension")
    }
}
