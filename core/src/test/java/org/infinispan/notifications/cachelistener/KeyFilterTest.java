package org.infinispan.notifications.cachelistener;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.infinispan.Cache;
import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.compat.TypeConverter;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.container.InternalEntryFactory;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.NonTxInvocationContext;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.filter.KeyFilter;
import org.infinispan.interceptors.impl.WrappedByteArrayConverter;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.notifications.cachelistener.cluster.ClusterEventManager;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachelistener.event.Event;
import org.infinispan.test.AbstractInfinispanTest;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test(testName = "notifications.cachelistener.KeyFilterTest", groups = "unit")
public class KeyFilterTest extends AbstractInfinispanTest {
   CacheNotifierImpl n;
   Cache mockCache;
   CacheListener cl;
   InvocationContext ctx;

   @BeforeMethod
   public void setUp() {
      KeyFilter kf = new KeyFilter() {
         @Override
         public boolean accept(Object key) {
            return key.toString().equals("accept");
         }
      };

      n = new CacheNotifierImpl();
      mockCache = mock(Cache.class, RETURNS_DEEP_STUBS);
      Configuration config = mock(Configuration.class, RETURNS_DEEP_STUBS);
      when(mockCache.getAdvancedCache().getStatus()).thenReturn(ComponentStatus.INITIALIZING);
      Answer answer = new Answer<Object>() {
         @Override
         public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
            return Mockito.mock((Class)invocationOnMock.getArguments()[0]);
         }
      };
      when(mockCache.getAdvancedCache().getComponentRegistry().getComponent(any(Class.class))).then(answer);
      when(mockCache.getAdvancedCache().getComponentRegistry().getComponent(TypeConverter.class)).thenReturn(
            new WrappedByteArrayConverter());
      when(mockCache.getAdvancedCache().getComponentRegistry().getComponent(any(Class.class), anyString())).then(answer);
      n.injectDependencies(mockCache, new ClusteringDependentLogic.LocalLogic(), null, config,
                           mock(DistributionManager.class), mock(InternalEntryFactory.class),
                           mock(ClusterEventManager.class));
      cl = new CacheListener();
      n.start();
      n.addListener(cl, kf);
      ctx = new NonTxInvocationContext(null, AnyEquivalence.getInstance());
   }

   public void testFilters() {
      n.notifyCacheEntryCreated("reject", "v1", null, true, ctx, null);
      n.notifyCacheEntryCreated("reject", "v1", null, false, ctx, null);

      assert !cl.isReceivedPost();
      assert !cl.isReceivedPre();
      assert cl.getInvocationCount() == 0;

      n.notifyCacheEntryCreated("accept", "v1", null, true, ctx, null);
      n.notifyCacheEntryCreated("accept", "v1", null, false, ctx, null);

      assert cl.isReceivedPost();
      assert cl.isReceivedPre();
      assert cl.getInvocationCount() == 2;
      assert cl.getEvents().get(0).getCache() == mockCache;
      assert cl.getEvents().get(0).getType() == Event.Type.CACHE_ENTRY_CREATED;
      assert ((CacheEntryCreatedEvent) cl.getEvents().get(0)).getKey().equals("accept");
      assert ((CacheEntryCreatedEvent) cl.getEvents().get(0)).getValue() == null;
      assert cl.getEvents().get(1).getCache() == mockCache;
      assert cl.getEvents().get(1).getType() == Event.Type.CACHE_ENTRY_CREATED;
      assert ((CacheEntryCreatedEvent) cl.getEvents().get(1)).getKey().equals("accept");
      assert ((CacheEntryCreatedEvent) cl.getEvents().get(1)).getValue().equals("v1");
   }
}
