package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.impl.ApplicationFactoryImpl;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.basic.MCompositePart;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimmedWindow;
import org.eclipse.e4.ui.model.application.ui.basic.impl.BasicFactoryImpl;
import org.eclipse.e4.ui.model.application.ui.menu.MDirectMenuItem;
import org.eclipse.e4.ui.model.application.ui.menu.MDirectToolItem;
import org.eclipse.e4.ui.model.application.ui.menu.MMenu;
import org.eclipse.e4.ui.model.application.ui.menu.MToolBar;
import org.eclipse.e4.ui.model.application.ui.menu.impl.MenuFactoryImpl;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.ModelVisibilityTool;

/**
 * The model search of eclipse_set_model_visibility reaches the menus and toolbars of parts, on a
 * model built without a workbench.
 */
class ModelVisibilitySearchTest {

	@Test
	void findsPartMenuAndToolbarItemsAndNamesTheirPart() throws Exception {
		MApplication application = ApplicationFactoryImpl.eINSTANCE.createApplication();
		MTrimmedWindow window = BasicFactoryImpl.eINSTANCE.createTrimmedWindow();
		application.getChildren().add(window);
		MPart part = BasicFactoryImpl.eINSTANCE.createPart();
		part.setElementId("test.view");
		window.getChildren().add(part);

		MMenu viewMenu = MenuFactoryImpl.eINSTANCE.createMenu();
		MDirectMenuItem menuItem = MenuFactoryImpl.eINSTANCE.createDirectMenuItem();
		menuItem.setElementId("test.item");
		viewMenu.getChildren().add(menuItem);
		part.getMenus().add(viewMenu);
		MToolBar toolBar = MenuFactoryImpl.eINSTANCE.createToolBar();
		MDirectToolItem toolItem = MenuFactoryImpl.eINSTANCE.createDirectToolItem();
		toolItem.setElementId("test.item");
		toolBar.getChildren().add(toolItem);
		part.setToolbar(toolBar);

		IEclipseContext context = EclipseContextFactory.create();
		context.set(IExtensionRegistry.class, Platform.getExtensionRegistry());
		// the implementation's package is x-friends of the platform only
		EModelService modelService = (EModelService) Class
				.forName("org.eclipse.e4.ui.internal.workbench.ModelServiceImpl", true, EModelService.class.getClassLoader())
				.getConstructor(IEclipseContext.class).newInstance(context);
		List<MUIElement> found = ModelVisibilityTool.find(modelService, application, "test.item");

		assertEquals(Set.of(menuItem, toolItem), Set.copyOf(found));
		assertEquals("test.view", ModelVisibilityTool.owningPart(modelService, menuItem));
		assertEquals("test.view", ModelVisibilityTool.owningPart(modelService, toolItem));
		assertNull(ModelVisibilityTool.owningPart(modelService, part));
		MCompositePart composite = BasicFactoryImpl.eINSTANCE.createCompositePart();
		window.getChildren().add(composite);
		MPart nested = BasicFactoryImpl.eINSTANCE.createPart();
		composite.getChildren().add(nested);
		assertNull(ModelVisibilityTool.owningPart(modelService, nested), "a nested part is not in a menu or toolbar");
	}
}
