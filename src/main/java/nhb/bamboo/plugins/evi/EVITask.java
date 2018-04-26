package nhb.bamboo.plugins.evi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.tools.ant.DirectoryScanner;

import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.task.CommonTaskContext;
import com.atlassian.bamboo.task.CommonTaskType;
import com.atlassian.bamboo.task.TaskException;
import com.atlassian.bamboo.task.TaskResult;
import com.atlassian.bamboo.task.TaskResultBuilder;
import com.atlassian.bamboo.variable.VariableDefinitionContext;

import lombok.AllArgsConstructor;

public class EVITask implements CommonTaskType {

	private static final String[] REGEX_SPECIAL_CHARS = new String[] { "\\", ".", "*", "+", "-", "[", "]", "(", ")",
			"$", "^", "|", "{", "}", "?" };

	private static final String normalizeForRegex(String key) {
		String result = key;
		for (String c : REGEX_SPECIAL_CHARS) {
			result = result.replaceAll("\\" + c, "\\\\\\" + c);
		}
		return result;
	}

	private static final String findAndReplace(String source, Map<String, String> variables, boolean ignoreCase,
			BuildLogger logger) {
		if (source == null) {
			return null;
		}

		if (variables == null) {
			return source;
		}

		String result = source;
		for (Object keyObj : variables.keySet()) {
			String key = keyObj.toString();
			String value = variables.get(key);
			result = result.replaceAll((ignoreCase ? "(?i)" : "") + normalizeForRegex(key), value);
		}
		return result;
	}

	private static final String getTextFileContent(File file) throws Exception {
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

	private static final Collection<String[]> extractVariableNameGroups(String text, String regex,
			boolean nonCaseSensitive, BuildLogger logger) {
		Collection<String[]> results = new LinkedList<>();
		Pattern pattern = Pattern.compile((nonCaseSensitive ? "(?i)" : "") + regex);
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			results.add(new String[] { matcher.group(0), matcher.group(1) });
		}
		return results;
	}

	private static final Set<File> scanForFiles(String workingFolder, String wildcardFileName) {
		File dir = new File(workingFolder);
		if (!dir.exists() || !dir.isDirectory()) {
			throw new IllegalArgumentException("Working directory must exist and is a folder");
		}

		DirectoryScanner scanner = new DirectoryScanner();
		scanner.setIncludes(new String[] { wildcardFileName });
		scanner.setBasedir(dir);
		scanner.setCaseSensitive(false);
		scanner.scan();

		Set<File> results = new HashSet<>();
		for (String fileName : scanner.getIncludedFiles()) {
			results.add(dir.toPath().resolve(fileName).toFile());
		}
		return results;
	}

	@Override
	public TaskResult execute(CommonTaskContext taskContext) throws TaskException {
		final BuildLogger logger = taskContext.getBuildLogger();

		final String regexPattern = taskContext.getConfigurationMap().get("pattern");
		final boolean ignoreCase = taskContext.getConfigurationMap().getAsBoolean("nonCaseSensitive");
		final boolean ignoreNonExistingVariables = taskContext.getConfigurationMap()
				.getAsBoolean("ignoreNonExistingVariables");

		final Map<String, String> allVariables = fetchAllVariables(taskContext, ignoreCase);

		// logger.addBuildLogEntry("All variables: " + allVariables);
		logger.addBuildLogEntry("Regex pattern: " + regexPattern);
		logger.addBuildLogEntry("Ignore case: " + ignoreCase);
		logger.addBuildLogEntry("Ignore non-existing variables: " + ignoreNonExistingVariables);

		final Collection<FileAndVariables> tobeProcessed = new LinkedList<>();

		final String filePaths = taskContext.getConfigurationMap().get("filePath");
		String[] wildcardFileNames = filePaths.split("\\r?\\n");
		for (String wildcardFileName : wildcardFileNames) {
			wildcardFileName = wildcardFileName.trim();
			if (wildcardFileName.length() > 0) {
				Set<File> matchedFiles = scanForFiles(taskContext.getWorkingDirectory().getAbsolutePath(),
						wildcardFileName);

				for (File file : matchedFiles) {
					String filePath = file.getAbsolutePath();
					String fileContent = null;
					logger.addBuildLogEntry("**************** Process variables injecting on file " + filePath);
					try {
						fileContent = getTextFileContent(file);
					} catch (Exception e) {
						throw new TaskException("Cannot get file content from " + filePath, e);
					}

					if (fileContent != null) {
						Collection<String[]> variableNameGroups = extractVariableNameGroups(fileContent, regexPattern,
								ignoreCase, logger);

						logger.addBuildLogEntry("Found " + variableNameGroups.size() + " variable name groups");

						Map<String, String> variables = new HashMap<>();
						for (String[] groups : variableNameGroups) {
							String key = ignoreCase ? groups[1].toLowerCase() : groups[1];
							if (allVariables.containsKey(key)) {
								String value = allVariables.get(key);
								logger.addBuildLogEntry("Mark to inject: " + groups[0]);

								variables.putIfAbsent(groups[0], value);
							} else if (ignoreNonExistingVariables) {
								logger.addBuildLogEntry("Skip " + groups[0] + " because of non-exists variable");
							} else {
								throw new TaskException("File '" + filePath + "' require a variable named '" + groups[1]
										+ "' (case " + (ignoreCase ? "in" : "")
										+ "sensitive), but that one cannot be found in environment variables");
							}
						}

						tobeProcessed.add(new FileAndVariables(filePath, fileContent, variables));
					}
				}
			}
		}

		for (FileAndVariables entry : tobeProcessed) {
			String newFileContent = null;

			try {
				newFileContent = findAndReplace(entry.fileContent, entry.variables, ignoreCase, logger);
			} catch (Exception e) {
				throw new TaskException("Inject variables error on file " + entry.filePath, e);
			}

			if (newFileContent != null) {
				try {
					writeTextContentToFile(newFileContent, entry.filePath);
				} catch (Exception e) {
					throw new TaskException("Write back file content to " + entry.filePath + " error", e);
				}
			}
		}

		return TaskResultBuilder.newBuilder(taskContext).success().build();
	}

	private Map<String, String> fetchAllVariables(CommonTaskContext taskContext, final boolean nonCaseSensitive) {
		final Map<String, String> allVariables = new HashMap<>();
		Map<String, VariableDefinitionContext> effectiveVariables = taskContext.getCommonContext().getVariableContext()
				.getEffectiveVariables();

		final Collection<VariableDefinitionContext> variables = effectiveVariables.values();
		for (final VariableDefinitionContext vdc : variables) {
			allVariables.put(nonCaseSensitive ? vdc.getKey().toLowerCase() : vdc.getKey(), vdc.getValue());
		}
		return allVariables;
	}

	@AllArgsConstructor
	private static class FileAndVariables {
		private final String filePath;
		private final String fileContent;
		private final Map<String, String> variables;
	}

}