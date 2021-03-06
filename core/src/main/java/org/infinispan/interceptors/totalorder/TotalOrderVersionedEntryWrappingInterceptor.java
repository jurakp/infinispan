package org.infinispan.interceptors.totalorder;

import java.util.ArrayList;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.VersionedPrepareCommand;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.ClusteredRepeatableReadEntry;
import org.infinispan.container.versioning.EntryVersion;
import org.infinispan.container.versioning.EntryVersionsMap;
import org.infinispan.container.versioning.IncrementableEntryVersion;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.interceptors.BasicInvocationStage;
import org.infinispan.interceptors.impl.VersionedEntryWrappingInterceptor;
import org.infinispan.metadata.EmbeddedMetadata;
import org.infinispan.metadata.Metadata;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Wrapping Interceptor for Total Order protocol when versions are needed
 *
 * @author Mircea.Markus@jboss.com
 * @author Pedro Ruivo
 */
public class TotalOrderVersionedEntryWrappingInterceptor extends VersionedEntryWrappingInterceptor {

   private static final Log log = LogFactory.getLog(TotalOrderVersionedEntryWrappingInterceptor.class);
   private static final boolean trace = log.isTraceEnabled();
   private static final EntryVersionsMap EMPTY_VERSION_MAP = new EntryVersionsMap();

   @Override
   public final BasicInvocationStage visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      if (ctx.isOriginLocal()) {
         ((VersionedPrepareCommand) command).setVersionsSeen(ctx.getCacheTransaction().getVersionsRead());
         //for local mode keys
         ctx.getCacheTransaction().setUpdatedEntryVersions(EMPTY_VERSION_MAP);
         return invokeNext(ctx, command).thenAccept((rCtx, rCommand, rv) -> {
            if (shouldCommitDuringPrepare((PrepareCommand) rCommand, ctx)) {
               commitContextEntries(ctx, null, null);
            }
         });
      }

      //Remote context, delivered in total order

      wrapEntriesForPrepare(ctx, command);

      return invokeNext(ctx, command).thenApply((rCtx, rCommand, rv) -> {
         TxInvocationContext txInvocationContext = (TxInvocationContext) rCtx;
         VersionedPrepareCommand prepareCommand = (VersionedPrepareCommand) rCommand;
         EntryVersionsMap versionsMap =
               cdl.createNewVersionsAndCheckForWriteSkews(versionGenerator, txInvocationContext,
                     prepareCommand);

         if (prepareCommand.isOnePhaseCommit()) {
            commitContextEntries(txInvocationContext, null, null);
         } else {
            if (trace)
               log.tracef("Transaction %s will be committed in the 2nd phase",
                     txInvocationContext.getGlobalTransaction().globalId());
         }

         return versionsMap == null ? rv : new ArrayList<Object>(versionsMap.keySet());
      });
   }

   @Override
   public BasicInvocationStage visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      return invokeNext(ctx, command).handle((rCtx, rCommand, rv, t) -> commitContextEntries(rCtx, null, null));
   }

   @Override
   protected void commitContextEntry(CacheEntry entry, InvocationContext ctx, FlagAffectedCommand command,
                                     Metadata metadata, Flag stateTransferFlag, boolean l1Invalidation) {
      if (ctx.isInTxScope() && stateTransferFlag == null) {
         Metadata commitMetadata;
         // If user provided version, use it, otherwise generate/increment accordingly
         ClusteredRepeatableReadEntry clusterMvccEntry = (ClusteredRepeatableReadEntry) entry;
         EntryVersion existingVersion = clusterMvccEntry.getMetadata().version();
         EntryVersion newVersion;
         if (existingVersion == null) {
            newVersion = versionGenerator.generateNew();
         } else {
            newVersion = versionGenerator.increment((IncrementableEntryVersion) existingVersion);
         }

         if (metadata == null)
            commitMetadata = new EmbeddedMetadata.Builder().version(newVersion).build();
         else
            commitMetadata = metadata.builder().version(newVersion).build();

         cdl.commitEntry(entry, commitMetadata, command, ctx, null, l1Invalidation);
      } else {
         // This could be a state transfer call!
         cdl.commitEntry(entry, entry.getMetadata(), command, ctx, stateTransferFlag, l1Invalidation);
      }
   }
}
