/*
 * Copyright 2006-2012 ICEsoft Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.icepdf.ri.common.views.annotations;

import org.icepdf.core.pobjects.annotations.Annotation;
import org.icepdf.core.views.DocumentViewController;
import org.icepdf.core.views.DocumentViewModel;
import org.icepdf.ri.common.views.AbstractPageViewComponent;

import java.awt.*;

/**
 * The PolygonAnnotationComponent encapsulates a PolygonAnnotation objects.
 * <p/>
 * NOTE: this component is has no handler and can only be created if the parent
 * Page object contains a PolygonAnnotation.
 *
 * @since 5.0
 */
public class PolygonAnnotationComponent extends MarkupAnnotationComponent {


    public PolygonAnnotationComponent(Annotation annotation, DocumentViewController documentViewController,
                                      AbstractPageViewComponent pageViewComponent, DocumentViewModel documentViewModel) {
        super(annotation, documentViewController, pageViewComponent, documentViewModel);
        isEditable = true;
        isRollover = true;
        isMovable = true;
        isResizable = true;
        isShowInvisibleBorder = true;
    }

    @Override
    public void resetAppearanceShapes() {

    }

    @Override
    public void paintComponent(Graphics g) {

    }
}