package com.vogella.eclipse.mcp.jdt.internal.corrections;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.internal.core.manipulation.CodeTemplateContextType;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.text.templates.ContextTypeRegistry;
import org.eclipse.text.templates.TemplatePersistenceData;
import org.eclipse.text.templates.TemplateStoreCore;

/**
 * Gives JDT's code generation the body templates it reads when a fix writes a method, constructor or catch block.
 * <p>
 * JDT UI installs its template store into {@link JavaManipulation} once it starts; until then, and always in a
 * headless IDE, the store is {@code null} and every such fix fails with a {@code NullPointerException} in
 * {@code ProjectTemplateStore}. JDT LS installs its own store the same way. The patterns are JDT UI's defaults.
 */
final class CodeTemplates {

	private static final String STORE_KEY = "com.vogella.eclipse.mcp.jdt.codetemplates"; //$NON-NLS-1$

	private CodeTemplates() {
	}

	static synchronized void ensureInstalled() {
		if (JavaManipulation.getCodeTemplateContextRegistry() == null) {
			ContextTypeRegistry registry = new ContextTypeRegistry();
			CodeTemplateContextType.registerContextTypes(registry);
			JavaManipulation.setCodeTemplateContextRegistry(registry);
		}
		if (JavaManipulation.getCodeTemplateStore() != null) {
			return;
		}
		// never saved, so the key only has to be unique
		TemplateStoreCore store = new TemplateStoreCore(InstanceScope.INSTANCE.getNode(JavaManipulation.ID_PLUGIN),
				STORE_KEY);
		add(store, CodeTemplateContextType.METHODSTUB_ID, CodeTemplateContextType.METHODBODY_CONTEXTTYPE,
				"// ${todo} Auto-generated method stub\n${body_statement}"); //$NON-NLS-1$
		add(store, CodeTemplateContextType.CONSTRUCTORSTUB_ID, CodeTemplateContextType.CONSTRUCTORBODY_CONTEXTTYPE,
				"${body_statement}\n// ${todo} Auto-generated constructor stub"); //$NON-NLS-1$
		add(store, CodeTemplateContextType.CATCHBLOCK_ID, CodeTemplateContextType.CATCHBLOCK_CONTEXTTYPE,
				"// ${todo} Auto-generated catch block\n${exception_var}.printStackTrace();"); //$NON-NLS-1$
		add(store, CodeTemplateContextType.GETTERSTUB_ID, CodeTemplateContextType.GETTERBODY_CONTEXTTYPE,
				"return ${field};"); //$NON-NLS-1$
		add(store, CodeTemplateContextType.SETTERSTUB_ID, CodeTemplateContextType.SETTERBODY_CONTEXTTYPE,
				"${field} = ${param};"); //$NON-NLS-1$
		JavaManipulation.setCodeTemplateStore(store);
	}

	private static void add(TemplateStoreCore store, String id, String contextType, String pattern) {
		store.add(new TemplatePersistenceData(new Template(id, "", contextType, pattern, false), true, id)); //$NON-NLS-1$
	}
}
