// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.ui.laf.darcula.ui;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ComponentUtil;
import com.intellij.ui.components.labels.DropDownLink;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.SwingTextTrimmer;
import sun.swing.SwingUtilities2;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.plaf.basic.BasicLabelUI;
import java.awt.*;

public class DarculaLabelUI extends BasicLabelUI {
  @SuppressWarnings({"MethodOverridesStaticMethodOfSuperclass", "unused"})
  public static ComponentUI createUI(JComponent c) {
    return new DarculaLabelUI();
  }

  @Override
  protected void paintEnabledText(JLabel l, Graphics g, String s, int textX, int textY) {
    g.setColor(l.getForeground());
    SwingUtilities2.drawStringUnderlineCharAt(l, g, s, getMnemonicIndex(l), textX, textY);
  }

  @Override
  protected void paintDisabledText(JLabel l, Graphics g, String s, int textX, int textY) {
    g.setColor(UIManager.getColor("Label.disabledForeground"));
    SwingUtilities2.drawStringUnderlineCharAt(l, g, s, -1, textX, textY);
  }

  protected int getMnemonicIndex(JLabel l) {
    return !SystemInfo.isMac ? l.getDisplayedMnemonicIndex() : -1;
  }

  @Override
  protected String layoutCL(JLabel label, FontMetrics fontMetrics, String text, Icon icon,
                            Rectangle viewR, Rectangle iconR, Rectangle textR) {
    String result = super.layoutCL(label, fontMetrics, text, icon, viewR, iconR, textR);
    if (!StringUtil.isEmpty(result)) {
      SwingTextTrimmer trimmer = ComponentUtil.getClientProperty(label, SwingTextTrimmer.KEY);
      if (trimmer != null && null == label.getClientProperty(BasicHTML.propertyKey)) {
        if (!result.equals(text) && result.endsWith(StringUtil.THREE_DOTS)) {
          result = trimmer.trim(text, fontMetrics, textR.width);
        }
      }
    }

    if (label instanceof DropDownLink) {
      iconR.y += JBUIScale.scale(1);
    }
    return result;
  }
}
