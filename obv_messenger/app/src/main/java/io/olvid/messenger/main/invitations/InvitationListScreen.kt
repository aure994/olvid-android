/*
 *  Olvid for Android
 *  Copyright © 2019-2023 Olvid SAS
 *
 *  This file is part of Olvid for Android.
 *
 *  Olvid is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License, version 3,
 *  as published by the Free Software Foundation.
 *
 *  Olvid is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with Olvid.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.olvid.messenger.main.invitations

//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.PaddingValues
//import androidx.compose.foundation.layout.Spacer
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.padding
//import androidx.compose.foundation.layout.requiredHeight
//import androidx.compose.foundation.lazy.LazyColumn
//import androidx.compose.foundation.lazy.itemsIndexed
//import androidx.compose.foundation.lazy.rememberLazyListState
//import androidx.compose.foundation.rememberScrollState
//import androidx.compose.foundation.verticalScroll
//import androidx.compose.material.ExperimentalMaterialApi
//import androidx.compose.material.pullrefresh.pullRefresh
//import androidx.compose.material.pullrefresh.rememberPullRefreshState
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.livedata.observeAsState
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.res.colorResource
//import androidx.compose.ui.text.AnnotatedString
//import androidx.compose.ui.unit.dp
//import com.google.accompanist.themeadapter.appcompat.AppCompatTheme
//import io.olvid.messenger.R
//import io.olvid.messenger.databases.entity.Invitation
//import io.olvid.messenger.main.EmptyListCard
//import io.olvid.messenger.main.RefreshingIndicator
//import io.olvid.messenger.main.invitations.InvitationListViewModel.Action

//@OptIn(ExperimentalMaterialApi::class)
//@Composable
//fun InvitationListScreen(
//    invitationListViewModel: InvitationListViewModel,
//    refreshing: Boolean,
//    onRefresh: () -> Unit,
//    onClick: (action: Action, invitation: Invitation, lastSas: String?) -> Unit,
//) {
//
//    val invitations by invitationListViewModel.invitations.observeAsState()
//    val refreshState = rememberPullRefreshState(refreshing, onRefresh)
//
//    AppCompatTheme {
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .pullRefresh(refreshState)
//        ) {
//            invitations?.let { list ->
//                if (list.isEmpty().not()) {
//                    LazyColumn(
//                        modifier = Modifier
//                            .fillMaxSize(),
//                        state = rememberLazyListState(),
//                        contentPadding = PaddingValues(bottom = 80.dp),
//                    ) {
//                        itemsIndexed(items = list) { index, invitation ->
//                            with(invitation) {
//                                val context = LocalContext.current
//                                Box {
//                                    InvitationListItem(
//                                        invitationListViewModel = invitationListViewModel,
//                                        invitation = invitation,
//                                        title = getAnnotatedTitle(context),
//                                        body = AnnotatedString(invitation.statusText),
//                                        date = getAnnotatedDate(context),
//                                        initialViewSetup = { initialView ->
//                                            invitationListViewModel.initialViewSetup(
//                                                initialView,
//                                                invitation
//                                            )
//                                        },
//                                        onClick = onClick,
//                                    )
//                                    if (index < list.size - 1) {
//                                        Spacer(
//                                            modifier = Modifier
//                                                .fillMaxWidth()
//                                                .padding(start = 84.dp, end = 12.dp)
//                                                .requiredHeight(1.dp)
//                                                .align(Alignment.BottomStart)
//                                                .background(
//                                                    color = colorResource(id = R.color.lightGrey)
//                                                )
//                                        )
//                                    }
//                                }
//                            }
//                        }
//                    }
//                } else {
//                    Box(
//                        modifier = Modifier
//                            .fillMaxSize()
//                            .verticalScroll(rememberScrollState())
//                    ) {
//                        EmptyListCard(stringRes = R.string.explanation_empty_invitation_list)
//                    }
//                }
//            }
//
//
//            RefreshingIndicator(
//                refreshing = refreshing,
//                refreshState = refreshState,
//            )
//        }
//    }
//}