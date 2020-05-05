/**
 * Copyright 2013- Mark C. Slee, Heron Arts LLC
 *
 * This file is part of the LX Studio software library. By using
 * LX, you agree to the terms of the LX Studio Software License
 * and Distribution Agreement, available at: http://lx.studio/license
 *
 * Please note that the LX license is not open-source. The license
 * allows for free, non-commercial use.
 *
 * HERON ARTS MAKES NO WARRANTY, EXPRESS, IMPLIED, STATUTORY, OR
 * OTHERWISE, AND SPECIFICALLY DISCLAIMS ANY WARRANTY OF
 * MERCHANTABILITY, NON-INFRINGEMENT, OR FITNESS FOR A PARTICULAR
 * PURPOSE, WITH RESPECT TO THE SOFTWARE.
 *
 * @author Mark C. Slee <mark@heronarts.com>
 */

package heronarts.lx;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import heronarts.lx.modulation.LXModulationContainer;
import heronarts.lx.modulation.LXModulationEngine;
import heronarts.lx.osc.LXOscComponent;
import heronarts.lx.parameter.BooleanParameter;

/**
 * A component which may have its own scoped user-level modulators. The concrete subclasses
 * of this are Patterns and Effects.
 */
public abstract class LXDeviceComponent extends LXLayeredComponent implements LXModulationContainer, LXOscComponent {

  public final LXModulationEngine modulation;

  private Throwable crash = null;

  public final BooleanParameter crashed =
    new BooleanParameter("Crashed", false)
    .setDescription("Set to true by the engine if this component fails in an unexpected way");

  public final BooleanParameter controlsExpanded =
    new BooleanParameter("Expanded", true)
    .setDescription("Whether the device controls are expanded");

  public final BooleanParameter modulationExpanded =
    new BooleanParameter("Modulation Expanded", false)
    .setDescription("Whether the device modulation section is expanded");

  protected LXDeviceComponent(LX lx) {
    this(lx, null);
  }

  protected LXDeviceComponent(LX lx, String label) {
    super(lx, label);
    addChild("modulation", this.modulation = new LXModulationEngine(lx));
    addInternalParameter("expanded", this.controlsExpanded);
    addInternalParameter("modulationExpanded", this.modulationExpanded);
  }

  @Override
  public void loop(double deltaMs) {
    if (!this.crashed.isOn()) {
      try {
        super.loop(deltaMs);
        this.modulation.loop(deltaMs);
      } catch (Exception x) {
        LX.error(x, "Unexpected exception in device loop " + getClass().getName() + ": " + x.getLocalizedMessage());
        this.lx.pushError(x, "Device " + LXUtils.getComponentName(getClass()) + " crashed due to an unexpected error.\n" + x.getLocalizedMessage());
        this.crash = x;
        this.crashed.setValue(true);
      }
    }
  }

  public Throwable getCrash() {
    return this.crash;
  }

  public String getCrashStackTrace() {
    try (StringWriter sw = new StringWriter()){
      this.crash.printStackTrace(new PrintWriter(sw));
      return sw.toString();
    } catch (IOException iox) {
      // This is fucked
      return null;
    }
  }

  public LXModulationEngine getModulationEngine() {
    return this.modulation;
  }

}
