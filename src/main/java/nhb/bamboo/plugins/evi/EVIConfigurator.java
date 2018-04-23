package nhb.bamboo.plugins.evi;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.atlassian.bamboo.collections.ActionParametersMap;
import com.atlassian.bamboo.task.AbstractTaskConfigurator;
import com.atlassian.bamboo.task.TaskDefinition;
import com.atlassian.bamboo.utils.error.ErrorCollection;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.message.I18nResolver;

@Component
public class EVIConfigurator extends AbstractTaskConfigurator {

	private final I18nResolver i18nResolver;

	@Autowired
	public EVIConfigurator(@ComponentImport("i18n") final I18nResolver i18nResolver) {
		this.i18nResolver = i18nResolver;
	}

	@NotNull
	public Map<String, String> generateTaskConfigMap(@NotNull final ActionParametersMap params,
			@Nullable final TaskDefinition previousTaskDefinition) {
		final Map<String, String> config = (Map<String, String>) super.generateTaskConfigMap(params,
				previousTaskDefinition);
		config.put("filePath", params.getString("filePath"));
		return config;
	}

	public void populateContextForCreate(@NotNull final Map<String, Object> context) {
		super.populateContextForCreate(context);
	}

	public void populateContextForEdit(@NotNull final Map<String, Object> context,
			@NotNull final TaskDefinition taskDefinition) {
		super.populateContextForEdit(context, taskDefinition);
		context.put("filePath", taskDefinition.getConfiguration().get("filePath"));
	}

	public void validate(@NotNull final ActionParametersMap params, @NotNull final ErrorCollection errorCollection) {
		super.validate(params, errorCollection);
		final String sayValue = params.getString("filePath");
		if (StringUtils.isBlank((CharSequence) sayValue)) {
			errorCollection.addError("filePath",
					this.i18nResolver.getText("nhb.bamboo.plugins.evi.text.filePathError"));
		}
	}
}
