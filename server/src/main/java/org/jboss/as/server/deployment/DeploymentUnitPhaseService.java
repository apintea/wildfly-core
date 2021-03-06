/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server.deployment;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.as.server.logging.ServerLogger;
import org.jboss.msc.service.AbstractServiceListener;
import org.jboss.msc.service.DelegatingServiceRegistry;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

/**
 * A service which executes a particular phase of deployment.
 *
 * @param <T> the public type of this deployment unit phase
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class DeploymentUnitPhaseService<T> implements Service<T> {

    private static final AttachmentKey<AttachmentList<DeploymentUnit>> UNVISITED_DEFERRED_MODULES = AttachmentKey.createList(DeploymentUnit.class);

    private final InjectedValue<DeployerChains> deployerChainsInjector = new InjectedValue<DeployerChains>();
    private final DeploymentUnit deploymentUnit;
    private final Phase phase;
    private final AttachmentKey<T> valueKey;
    private final List<AttachedDependency> injectedAttachedDependencies = new ArrayList<AttachedDependency>();
    /**
     * boolean value that tracks if this phase has already been run.
     *
     * If anything attempts to restart the phase a complete deployment restart is performed instead.
     */
    private final AtomicBoolean runOnce = new AtomicBoolean();

    /**
     * If this is true then when a deployment goes down due to a dependency restart it will immediately attempt redeployment,
     * otherwise it will wait till the dependency is available before doing the restart.
     *
     * At present this behaviour has a high chance of hitting MSC race conditions, that are only prevented at this stage
     * by using the new directional executor. As this executor is only enabled via the <code>org.jboss.msc.directionalExecutor</code>
     * system property this new restart behaviour is also only enabled if this system property is set.
     *
     * Once these MSC issues are fixed this should be removed, and immediate deployment restart should be made the default,
     * as the current behaviour can cause infinite MSC looping in some circumstances if optional dependencies are in use
     * between deployments.
     */
    private static final boolean immediateDeploymentRestart;

    static {
        immediateDeploymentRestart = Boolean.getBoolean("org.jboss.msc.directionalExecutor");
    }

    private DeploymentUnitPhaseService(final DeploymentUnit deploymentUnit, final Phase phase, final AttachmentKey<T> valueKey) {
        this.deploymentUnit = deploymentUnit;
        this.phase = phase;
        this.valueKey = valueKey;
    }

    private static <T> DeploymentUnitPhaseService<T> create(final DeploymentUnit deploymentUnit, final Phase phase, AttachmentKey<T> valueKey) {
        return new DeploymentUnitPhaseService<T>(deploymentUnit, phase, valueKey);
    }

    static DeploymentUnitPhaseService<?> create(final DeploymentUnit deploymentUnit, final Phase phase) {
        return create(deploymentUnit, phase, phase.getPhaseKey());
    }

    @SuppressWarnings("unchecked")
    public synchronized void start(final StartContext context) throws StartException {
        boolean allowRestart = restartAllowed();
        if(!immediateDeploymentRestart && runOnce.get() && !allowRestart) {
            ServerLogger.DEPLOYMENT_LOGGER.deploymentRestartDetected(deploymentUnit.getName());
            //this only happens on deployment restart, which we don't support at the moment.
            //instead we are going to restart the complete deployment.

            //we get the deployment unit service name
            //add a listener to perform a restart when the service goes down
            //then stop the deployment unit service
            final ServiceName serviceName;
            if(deploymentUnit.getParent() == null) {
                serviceName = deploymentUnit.getServiceName();
            } else {
                serviceName = deploymentUnit.getParent().getServiceName();
            }
            ServiceController<?> controller = context.getController().getServiceContainer().getRequiredService(serviceName);
            controller.addListener(new AbstractServiceListener<Object>() {

                @Override
                public void transition(final ServiceController<?> controller, final ServiceController.Transition transition) {
                    if(transition.getAfter().equals(ServiceController.Substate.DOWN)) {
                        controller.setMode(Mode.ACTIVE);
                        controller.removeListener(this);
                    }
                }
            });
            controller.setMode(Mode.NEVER);
            return;
        }
        runOnce.set(true);
        final DeployerChains chains = deployerChainsInjector.getValue();
        final DeploymentUnit deploymentUnit = this.deploymentUnit;
        final List<RegisteredDeploymentUnitProcessor> list = chains.getChain(phase);
        final ListIterator<RegisteredDeploymentUnitProcessor> iterator = list.listIterator();
        final ServiceContainer container = context.getController().getServiceContainer();
        final ServiceTarget serviceTarget = context.getChildTarget().subTarget();
        final String name = deploymentUnit.getName();
        final DeploymentUnit parent = deploymentUnit.getParent();

        final List<DeploymentUnitPhaseDependency> dependencies = new LinkedList<>();
        final DeploymentPhaseContext processorContext = new DeploymentPhaseContextImpl(serviceTarget, new DelegatingServiceRegistry(container), dependencies, deploymentUnit, phase);

        // attach any injected values from the last phase
        for (AttachedDependency attachedDependency : injectedAttachedDependencies) {
            final Attachable target;
            if (attachedDependency.isDeploymentUnit()) {
                target = deploymentUnit;
            } else {
                target = processorContext;
            }
            if (attachedDependency.getAttachmentKey() instanceof ListAttachmentKey) {
                target.addToAttachmentList((AttachmentKey) attachedDependency.getAttachmentKey(), attachedDependency.getValue().getValue());
            } else {
                target.putAttachment((AttachmentKey) attachedDependency.getAttachmentKey(), attachedDependency.getValue().getValue());
            }
        }

        while (iterator.hasNext()) {
            final RegisteredDeploymentUnitProcessor processor = iterator.next();
            try {
                if (shouldRun(deploymentUnit, processor)) {
                    processor.getProcessor().deploy(processorContext);
                }
            } catch (Throwable e) {
                while (iterator.hasPrevious()) {
                    final RegisteredDeploymentUnitProcessor prev = iterator.previous();
                    safeUndeploy(deploymentUnit, phase, prev);
                }
                throw ServerLogger.ROOT_LOGGER.deploymentPhaseFailed(phase, deploymentUnit, e);
            }
        }

        final Phase nextPhase = phase.next();
        if (nextPhase != null) {
            final ServiceName serviceName = DeploymentUtils.getDeploymentUnitPhaseServiceName(deploymentUnit, nextPhase);
            final DeploymentUnitPhaseService<?> phaseService = DeploymentUnitPhaseService.create(deploymentUnit, nextPhase);
            final ServiceBuilder<?> phaseServiceBuilder = serviceTarget.addService(serviceName, phaseService);

            for (DeploymentUnitPhaseDependency dependency: dependencies) {
                dependency.register(phaseServiceBuilder);
            }

            phaseServiceBuilder.addDependency(Services.JBOSS_DEPLOYMENT_CHAINS, DeployerChains.class, phaseService.getDeployerChainsInjector());
            phaseServiceBuilder.addDependency(context.getController().getName());

            final List<ServiceName> nextPhaseDeps = processorContext.getAttachment(Attachments.NEXT_PHASE_DEPS);
            if (nextPhaseDeps != null) {
                phaseServiceBuilder.addDependencies(nextPhaseDeps);
            }
            final List<AttachableDependency> nextPhaseAttachableDeps = processorContext.getAttachment(Attachments.NEXT_PHASE_ATTACHABLE_DEPS);
            if (nextPhaseAttachableDeps != null) {
                for (AttachableDependency attachableDep : nextPhaseAttachableDeps) {
                    AttachedDependency result = new AttachedDependency(attachableDep.getAttachmentKey(), attachableDep.isDeploymentUnit());
                    phaseServiceBuilder.addDependency(attachableDep.getServiceName(), result.getValue());
                    phaseService.injectedAttachedDependencies.add(result);

                }
            }

            // Add a dependency on the parent's next phase
            if (parent != null) {
                phaseServiceBuilder.addDependencies(Services.deploymentUnitName(parent.getName(), nextPhase));
            }

            // Make sure all sub deployments have finished this phase before moving to the next one
            List<DeploymentUnit> subDeployments = deploymentUnit.getAttachmentList(Attachments.SUB_DEPLOYMENTS);
            for (DeploymentUnit du : subDeployments) {
                phaseServiceBuilder.addDependencies(du.getServiceName().append(phase.name()));
            }

            // Defer the {@link Phase.FIRST_MODULE_USE} phase
            List<String> deferredModules = DeploymentUtils.getDeferredModules(deploymentUnit);
            if (nextPhase == Phase.FIRST_MODULE_USE) {
                Mode initialMode = getDeferableInitialMode(deploymentUnit, deferredModules);
                if (initialMode != Mode.ACTIVE) {
                    ServerLogger.DEPLOYMENT_LOGGER.infoDeferDeploymentPhase(nextPhase, name, initialMode);
                    phaseServiceBuilder.setInitialMode(initialMode);
                }
            }

            phaseServiceBuilder.install();
        }
    }

    private Boolean restartAllowed() {
        final DeploymentUnit parent;
        if (deploymentUnit.getParent() == null) {
            parent = deploymentUnit;
        } else {
            parent = deploymentUnit.getParent();
        }
        Boolean allowed = parent.getAttachment(Attachments.ALLOW_PHASE_RESTART);
        return allowed != null && allowed;
    }

    public synchronized void stop(final StopContext context) {
        if(immediateDeploymentRestart && !restartAllowed()) {
            final DeploymentUnit topDeployment = deploymentUnit.getParent() != null ? deploymentUnit.getParent() : deploymentUnit;
            final ServiceName top = topDeployment.getServiceName();
            final ServiceController<?> topController = context.getController().getServiceContainer().getService(top);
            final Mode mode = topController.getMode();
            if (mode != Mode.REMOVE && mode != Mode.NEVER && !context.getController().getServiceContainer().isShutdown()) {
                //the deployment is going down, but it has not been explicitly stopped or removed
                //so it must be because of a missing dependency. Unfortunately these phase services cannot fully restart
                //as the data they require no longer exists, so instead we trigger a complete redeployment
                //the redeployment will likely not fully complete, but will be waiting on whatever missing dependency
                //caused this stop

                //add a listener to perform a restart when the service goes down
                //then stop the deployment unit service
                final AbstractServiceListener<Object> serviceListener = new AbstractServiceListener<Object>() {

                    @Override
                    public void transition(final ServiceController<?> controller, final ServiceController.Transition transition) {
                        if (transition.getAfter().equals(ServiceController.Substate.DOWN)) {
                            //its possible an undeploy happened in the meantime
                            //so we use compareAndSetMode to make sure the mode is still NEVER and not REMOVE
                            controller.compareAndSetMode(Mode.NEVER, mode);
                            controller.removeListener(this);
                        }
                    }
                };
                topController.addListener(serviceListener);
                if (topController.compareAndSetMode(mode, Mode.NEVER)) {
                    ServerLogger.DEPLOYMENT_LOGGER.deploymentRestartDetected(topDeployment.getName());
                } else {
                    topController.removeListener(serviceListener);
                }
            }
        }
        final DeploymentUnit deploymentUnitContext = deploymentUnit;
        final DeployerChains chains = deployerChainsInjector.getValue();
        final List<RegisteredDeploymentUnitProcessor> list = chains.getChain(phase);
        final ListIterator<RegisteredDeploymentUnitProcessor> iterator = list.listIterator(list.size());
        while (iterator.hasPrevious()) {
            final RegisteredDeploymentUnitProcessor prev = iterator.previous();
            safeUndeploy(deploymentUnitContext, phase, prev);
        }
    }

    private Mode getDeferableInitialMode(final DeploymentUnit deploymentUnit, List<String> deferredModules) {
        // Make the deferred module NEVER
        if (deferredModules.contains(deploymentUnit.getName())) {
            return Mode.NEVER;
        }
        Mode initialMode = Mode.ACTIVE;
        DeploymentUnit parent = DeploymentUtils.getTopDeploymentUnit(deploymentUnit);
        if (parent == deploymentUnit) {
            List<DeploymentUnit> subDeployments = parent.getAttachmentList(Attachments.SUB_DEPLOYMENTS);
            for (DeploymentUnit du : subDeployments) {
                // Always make the EAR LAZY if it could contain deferrable sub-deployments
                if (du.hasAttachment(Attachments.OSGI_MANIFEST)) {
                    initialMode = Mode.LAZY;
                    break;
                }
            }
            // Initialize the list of unvisited deferred modules
            if (initialMode == Mode.LAZY) {
                for (DeploymentUnit du : subDeployments) {
                    parent.addToAttachmentList(UNVISITED_DEFERRED_MODULES, du);
                }
            }
        } else {
            // Make the non-deferred sibling PASSIVE if it is not the last to visit
            List<DeploymentUnit> unvisited = parent.getAttachmentList(UNVISITED_DEFERRED_MODULES);
            synchronized (unvisited) {
                unvisited.remove(deploymentUnit);
                if (!deferredModules.isEmpty() || !unvisited.isEmpty()) {
                    initialMode = Mode.PASSIVE;
                }
            }
        }
        return initialMode;
    }

    private static void safeUndeploy(final DeploymentUnit deploymentUnit, final Phase phase, final RegisteredDeploymentUnitProcessor prev) {
        try {
            if (shouldRun(deploymentUnit, prev)) {
                prev.getProcessor().undeploy(deploymentUnit);
            }
        } catch (Throwable t) {
            ServerLogger.DEPLOYMENT_LOGGER.caughtExceptionUndeploying(t, prev.getProcessor(), phase, deploymentUnit);
        }
    }

    public synchronized T getValue() throws IllegalStateException, IllegalArgumentException {
        return deploymentUnit.getAttachment(valueKey);
    }

    InjectedValue<DeployerChains> getDeployerChainsInjector() {
        return deployerChainsInjector;
    }

    private static boolean shouldRun(final DeploymentUnit unit, final RegisteredDeploymentUnitProcessor deployer) {
        Set<String> shouldNotRun = unit.getAttachment(Attachments.EXCLUDED_SUBSYSTEMS);
        if (shouldNotRun == null) {
            if (unit.getParent() != null) {
                shouldNotRun = unit.getParent().getAttachment(Attachments.EXCLUDED_SUBSYSTEMS);
            }
            if (shouldNotRun == null) {
                return true;
            }
        }
        return !shouldNotRun.contains(deployer.getSubsystemName());
    }
}
