package nhb.bamboo.plugins.evi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;

import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.task.CommonTaskContext;
import com.atlassian.bamboo.task.CommonTaskType;
import com.atlassian.bamboo.task.TaskException;
import com.atlassian.bamboo.task.TaskResult;
import com.atlassian.bamboo.task.TaskResultBuilder;
import com.atlassian.bamboo.variable.VariableDefinitionContext;

public class EVITask implements CommonTaskType {

	private static final String[] REGEX_SPECIAL_CHARS = new String[] { ".", "*", "-", "[", "]", "(", ")", "$", "^" };

	private static final String normalizeKey(String key) {
		String result = key;
		for (String c : REGEX_SPECIAL_CHARS) {
			result = result.replaceAll("\\" + c, "\\\\" + c);
		}
		return result;
	}

	private static final String findAndReplace(String source, Properties variables) {
		if (source == null) {
			return null;
		}

		if (variables == null) {
			return source;
		}

		String result = source;
		for (Object keyObj : variables.keySet()) {
			String key = keyObj.toString();
			String variableKey = "\\$\\{" + normalizeKey(key) + "\\}";
			String variableValue = variables.getProperty(key);
			result = result.replaceAll(variableKey, variableValue);
		}
		return result;
	}

	private static final String getTextFileContent(String filePath) throws Exception {
		File file = new File(filePath);

		try (InputStream is = new FileInputStream(file); StringWriter sw = new StringWriter()) {
			IOUtils.copy(is, sw);
			return sw.toString();
		}
	}

	private static final void writeTextContentToFile(String content, String filePath) throws Exception {
		File file = new File(filePath);
		if (!file.exists()) {
			file.createNewFile();
		}

		try (OutputStream os = new FileOutputStream(file)) {
			IOUtils.write(content, os);
		}
	}

	@Override
	public TaskResult execute(CommonTaskContext taskContext) throws TaskException {
		final BuildLogger logger = taskContext.getBuildLogger();

		String filePaths = taskContext.getConfigurationMap().get("filePath");
		String[] paths = filePaths.split("\\r?\\n");

		for (String filePath : paths) {
			filePath = filePath.trim();
			if (filePath.length() > 0) {
				filePath = taskContext.getWorkingDirectory() + "/" + filePath;
				logger.addBuildLogEntry("File to be injected variables: " + filePath);

				String fileContent = null;
				try {
					fileContent = getTextFileContent(filePath);
				} catch (Exception e) {
					throw new TaskException("Cannot get file content from " + filePath, e);
				}

				Map<String, VariableDefinitionContext> effectiveVariables = taskContext.getCommonContext()
						.getVariableContext().getEffectiveVariables();

				Properties props = new Properties();
				Collection<VariableDefinitionContext> variables = effectiveVariables.values();
				for (final VariableDefinitionContext vdc : variables) {
					props.put(vdc.getKey(), vdc.getValue());
				}

				// logger.addBuildLogEntry("Environment variables: " + props.toString());

				fileContent = findAndReplace(fileContent, props);

				try {
					writeTextContentToFile(fileContent, filePath);
				} catch (Exception e) {
					throw new TaskException("Cannot write content to file " + filePath, e);
				}
			}
		}

		return TaskResultBuilder.newBuilder(taskContext).success().build();
	}
}