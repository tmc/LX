/**
 * Copyright 2018- Mark C. Slee, Heron Arts LLC
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

package heronarts.lx.mixer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.LXModulatorComponent;
import heronarts.lx.ModelBuffer;
import heronarts.lx.blend.LXBlend;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.ObjectParameter;

/**
 * Abstract subclass for both groups and channels
 */
public abstract class LXAbstractChannel extends LXBus implements LXComponent.Renamable {

  public interface Listener extends LXBus.Listener {
    public default void indexChanged(LXAbstractChannel channel) {}
  }

  private final List<Listener> listeners = new ArrayList<Listener>();

  public class Profiler extends LXBus.Profiler {
    public long blendNanos;
  }

  @Override
  protected LXModulatorComponent.Profiler constructProfiler() {
    return new Profiler();
  }

  public enum CrossfadeGroup {
    BYPASS,
    A,
    B
  };

  // An internal state flag used by the engine to track which channels
  // are actively animating (e.g. they are enabled or cued)
  boolean isAnimating;

  /**
   * The index of this channel in the engine.
   */
  protected int index;

  /**
   * This is a local buffer used for transition blending on this channel
   */
  protected final ModelBuffer blendBuffer;

  protected int[] colors;

  /**
   * Whether this channel is enabled.
   */
  public final BooleanParameter enabled =
    new BooleanParameter("On", true)
    .setDescription("Sets whether this channel is on or off");

  /**
   * Crossfade group this channel belongs to
   */
  public final EnumParameter<CrossfadeGroup> crossfadeGroup =
    new EnumParameter<CrossfadeGroup>("Group", CrossfadeGroup.BYPASS)
    .setDescription("Assigns this channel to crossfader group A or B");

  /**
   * Whether this channel should show in the cue UI.
   */
  public final BooleanParameter cueActive =
    new BooleanParameter("Cue", false)
    .setDescription("Toggles the channel CUE state, determining whether it is shown in the preview window");

  public final CompoundParameter fader =
    new CompoundParameter("Fader", 1)
    .setDescription("Sets the alpha level of the output of this channel");

  public final ObjectParameter<LXBlend> blendMode;

  private LXBlend activeBlend;

  final ChannelThread thread = new ChannelThread();

  private static int channelThreadCount = 1;

  class ChannelThread extends Thread {

    ChannelThread() {
      super("LXChannel thread #" + channelThreadCount++);
    }

    boolean hasStarted = false;
    boolean workReady = true;
    double deltaMs;

    class Signal {
      boolean workDone = false;
    }

    Signal signal = new Signal();

    @Override
    public void run() {
      LX.log("LXEngine Channel thread started [" + getLabel() + "]");
      while (!isInterrupted()) {
        synchronized (this) {
          try {
            while (!this.workReady) {
              wait();
            }
          } catch (InterruptedException ix) {
            // Channel is finished
            break;
          }
          this.workReady = false;
        }
        loop(this.deltaMs);
        synchronized (this.signal) {
          this.signal.workDone = true;
          this.signal.notify();
        }
      }
      LX.log("LXEngine Channel thread finished [" + getLabel() + "]");
    }
  };

  protected LXAbstractChannel(LX lx, int index, String label) {
    super(lx, label);
    this.index = index;
    this.label.setDescription("The name of this channel");
    this.blendBuffer = new ModelBuffer(lx);
    this.colors = this.blendBuffer.getArray();

    this.blendMode = new ObjectParameter<LXBlend>("Blend", new LXBlend[1])
      .setDescription("Specifies the blending function used for the channel fader");
    updateChannelBlendOptions();

    addParameter("enabled", this.enabled);
    addParameter("cue", this.cueActive);
    addParameter("fader", this.fader);
    addParameter("crossfadeGroup", this.crossfadeGroup);
    addParameter("blendMode", this.blendMode);
  }

  void updateChannelBlendOptions() {
    for (LXBlend blend : this.blendMode.getObjects()) {
      if (blend != null) {
        blend.dispose();
      }
    }
    this.blendMode.setObjects(lx.engine.mixer.instantiateChannelBlends());
    this.activeBlend = this.blendMode.getObject();
    this.activeBlend.onActive();
  }

  @Override
  public String getPath() {
    return LXMixerEngine.PATH_CHANNEL + "/" + (this.index+1);
  }

  @Override
  public void onParameterChanged(LXParameter p) {
    super.onParameterChanged(p);
    if (p == this.cueActive) {
      if (this.cueActive.isOn()) {
        this.lx.engine.mixer.cueA.setValue(false);
        this.lx.engine.mixer.cueB.setValue(false);
        if (this.lx.flags.focusChannelOnCue) {
          this.lx.engine.mixer.selectChannel(this);
          this.lx.engine.mixer.setFocusedChannel(this);
        }
      }
    } else if (p == this.blendMode) {
      this.activeBlend.onInactive();
      this.activeBlend = this.blendMode.getObject();
      this.activeBlend.onActive();
    }
  }

  private boolean isAnimating() {
    // Cue is active? We must loop to preview ourselves
    if (this.cueActive.isOn()) {
      return true;
    }
    // We're not active? Then we're disabled for sure
    if (!this.enabled.isOn()) {
      return false;
    }
    // Are we a group? Cool, we should animate.
    if (this instanceof LXGroup) {
      return true;
    }
    // Are we *in* a group? Only animate if that group is animating,
    // otherwise if no group then we are good to go
    LXGroup group = getGroup();
    return (group == null) || group.isAnimating;
  }

  @Override
  public void loop(double deltaMs) {
    // Figure out if we need to loop components and modulators etc.
    this.isAnimating = isAnimating();
    super.loop(deltaMs, this.isAnimating);
  }

  public final void addListener(Listener listener) {
    Objects.requireNonNull(listener, "May not add null LXAbstractChannel.Listener");
    if (this.listeners.contains(listener)) {
      throw new IllegalStateException("May not add duplicate LXAbstractChannel.Listener: " + listener);
    }
    super.addListener(listener);
    this.listeners.add(listener);
  }

  public final void removeListener(Listener listener) {
    if (!this.listeners.contains(listener)) {
      throw new IllegalStateException("May not remove non-registered LXAbstractChannel.Listener: " + listener);
    }
    super.removeListener(listener);
    this.listeners.remove(listener);
  }

  final LXAbstractChannel setIndex(int index) {
    if (this.index != index) {
      this.index = index;
      for (LXAbstractChannel.Listener listener : this.listeners) {
        listener.indexChanged(this);
      }
    }
    return this;
  }

  @Override
  public final int getIndex() {
    return this.index;
  }

  int[] getColors() {
    return this.colors;
  }

  @Override
  public void dispose() {
    this.blendBuffer.dispose();
    this.listeners.clear();
    synchronized (this.thread) {
      this.thread.interrupt();
    }
    super.dispose();
  }

}
