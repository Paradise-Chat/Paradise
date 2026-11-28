import Foundation
import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@available(iOS 17.0, *)
struct ParadiseCallLockScreenView: View {
    let context: ActivityViewContext<ParadiseCallActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                ZStack {
                    Circle()
                        .fill(Color(red: 0.14, green: 0.65, blue: 0.35).opacity(0.22))
                        .frame(width: 38, height: 38)
                    Image(systemName: context.state.hasVideo ? "video.fill" : "waveform")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color(red: 0.25, green: 0.85, blue: 0.48))
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(context.state.callerName)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)

                    HStack(spacing: 6) {
                        Text( subtitleText )
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.white.opacity(0.72))

                        if context.state.isConnected, let start = context.state.connectedAt {
                            Text("·")
                                .foregroundStyle(.white.opacity(0.5))
                            Text(timerInterval: start...Date.distantFuture, countsDown: false)
                                .font(.system(size: 12, weight: .semibold).monospacedDigit())
                                .foregroundStyle(Color(red: 0.25, green: 0.85, blue: 0.48))
                        }
                    }
                }

                Spacer(minLength: 8)
            }

            HStack(spacing: 10) {
                Button(intent: ToggleParadiseCallMuteIntent(roomId: context.attributes.roomId)) {
                    HStack(spacing: 6) {
                        Image(systemName: context.state.isMuted ? "mic.slash.fill" : "mic.fill")
                        Text(context.state.isMuted ? "Unmute" : "Mute")
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(
                                context.state.isMuted
                                    ? Color.orange.opacity(0.28)
                                    : Color.white.opacity(0.14)
                            )
                    )
                    .foregroundStyle(.white)
                }
                .buttonStyle(.plain)

                Button(intent: LeaveParadiseCallIntent(roomId: context.attributes.roomId)) {
                    HStack(spacing: 6) {
                        Image(systemName: "phone.down.fill")
                        Text("Leave")
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(Color(red: 0.85, green: 0.22, blue: 0.24))
                    )
                    .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .activityBackgroundTint(Color(red: 0.11, green: 0.12, blue: 0.14))
        .activitySystemActionForegroundColor(.white)
    }

    private var subtitleText: String {
        if !context.state.isConnected {
            return "Connecting..."
        }
        if context.state.isMuted {
            return "Paradise · Mic Muted"
        }
        return context.state.hasVideo ? "Paradise Video" : "Paradise Voice"
    }
}

@available(iOS 17.0, *)
struct ParadiseCallLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ParadiseCallActivityAttributes.self) { context in
            ParadiseCallLockScreenView(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 8) {
                        Image(systemName: context.state.hasVideo ? "video.fill" : "waveform")
                            .foregroundStyle(Color(red: 0.25, green: 0.85, blue: 0.48))
                        Text(context.state.callerName)
                            .font(.system(size: 14, weight: .semibold))
                            .lineLimit(1)
                    }
                    .padding(.leading, 4)
                }

                DynamicIslandExpandedRegion(.trailing) {
                    if context.state.isConnected, let start = context.state.connectedAt {
                        Text(timerInterval: start...Date.distantFuture, countsDown: false)
                            .font(.system(size: 13, weight: .semibold).monospacedDigit())
                            .foregroundStyle(Color(red: 0.25, green: 0.85, blue: 0.48))
                            .multilineTextAlignment(.trailing)
                            .frame(width: 58, alignment: .trailing)
                    } else {
                        Text("Dialing")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                }

                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 10) {
                        Button(intent: ToggleParadiseCallMuteIntent(roomId: context.attributes.roomId)) {
                            Label(
                                context.state.isMuted ? "Unmute" : "Mute",
                                systemImage: context.state.isMuted ? "mic.slash.fill" : "mic.fill"
                            )
                            .font(.system(size: 13, weight: .semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 7)
                            .background(
                                Capsule()
                                    .fill(
                                        context.state.isMuted
                                            ? Color.orange.opacity(0.32)
                                            : Color.white.opacity(0.16)
                                    )
                            )
                        }
                        .buttonStyle(.plain)

                        Button(intent: LeaveParadiseCallIntent(roomId: context.attributes.roomId)) {
                            Label("Leave", systemImage: "phone.down.fill")
                                .font(.system(size: 13, weight: .semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 7)
                                .background(
                                    Capsule()
                                        .fill(Color(red: 0.85, green: 0.22, blue: 0.24))
                                )
                                .foregroundStyle(.white)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.top, 4)
                }
            } compactLeading: {
                Image(systemName: context.state.isMuted ? "mic.slash.fill" : "waveform")
                    .foregroundStyle(
                        context.state.isMuted
                            ? Color.orange
                            : Color(red: 0.25, green: 0.85, blue: 0.48)
                    )
            } compactTrailing: {
                if context.state.isConnected, let start = context.state.connectedAt {
                    Text(timerInterval: start...Date.distantFuture, countsDown: false)
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundStyle(Color(red: 0.25, green: 0.85, blue: 0.48))
                        .frame(width: 44)
                } else {
                    Image(systemName: "ellipsis")
                        .foregroundStyle(.secondary)
                }
            } minimal: {
                Image(systemName: context.state.isMuted ? "mic.slash.fill" : "waveform")
                    .foregroundStyle(
                        context.state.isMuted
                            ? Color.orange
                            : Color(red: 0.25, green: 0.85, blue: 0.48)
                    )
            }
        }
    }
}

@available(iOS 17.0, *)
@main
struct ParadiseCallWidgetBundle: WidgetBundle {
    var body: some Widget {
        ParadiseCallLiveActivity()
    }
}
