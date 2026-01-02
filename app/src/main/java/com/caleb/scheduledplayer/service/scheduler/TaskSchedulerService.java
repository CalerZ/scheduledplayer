package com.caleb.scheduledplayer.service.scheduler;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.caleb.scheduledplayer.data.database.AppDatabase;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.service.player.AudioPlaybackService;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务调度服务
 * 使用 AlarmManager 设置精确闹钟
 */
public class TaskSchedulerService {

    private static final String TAG = "TaskSchedulerService";
    private static final int REQUEST_CODE_START_PREFIX = 10000;
    private static final int REQUEST_CODE_STOP_PREFIX = 20000;

    private final Context context;
    private final AlarmManager alarmManager;
    private final ExecutorService executorService;

    public TaskSchedulerService(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        this.executorService = Executors.newSingleThreadExecutor();
    }

    /**
     * 调度单个任务
     */
    public void scheduleTask(TaskEntity task) {
        if (!task.isEnabled()) {
            cancelTask(task.getId());
            return;
        }

        // 解析开始时间
        String[] timeParts = task.getStartTime().split(":");
        int startHour = Integer.parseInt(timeParts[0]);
        int startMinute = Integer.parseInt(timeParts[1]);

        // 当前时间
        long now = System.currentTimeMillis();
        
        // 计算今天的开始时间
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.set(Calendar.HOUR_OF_DAY, startHour);
        startCalendar.set(Calendar.MINUTE, startMinute);
        startCalendar.set(Calendar.SECOND, 0);
        startCalendar.set(Calendar.MILLISECOND, 0);
        
        long todayStartTime = startCalendar.getTimeInMillis();
        
        // 检查今天是否需要执行
        int repeatDays = task.getRepeatDays();
        boolean shouldRunToday = true;
        if (repeatDays != 0) {
            int todayFlag = getDayFlag(Calendar.getInstance().get(Calendar.DAY_OF_WEEK));
            shouldRunToday = (repeatDays & todayFlag) != 0;
        }

        // 全天播放模式
        if (task.isAllDayPlay()) {
            Log.d(TAG, "Task " + task.getId() + " is all-day play mode");
            
            if (shouldRunToday && now >= todayStartTime) {
                // 今天的开始时间已过，立即开始播放
                Log.d(TAG, "Task " + task.getId() + " all-day mode: starting immediately");
                AudioPlaybackService.startTaskPlayback(context, task.getId());
            } else if (shouldRunToday) {
                // 今天的开始时间未到，停止播放（如果正在播放），设置开始闹钟
                Log.d(TAG, "Task " + task.getId() + " all-day mode: start time not reached, stopping if playing");
                AudioPlaybackService.stopTaskPlayback(context, task.getId());
                setAlarm(task.getId(), todayStartTime, true);
                Log.d(TAG, "Task " + task.getId() + " all-day mode: scheduled start at " + startCalendar.getTime());
            } else {
                // 今天不需要执行，停止播放（如果正在播放）
                Log.d(TAG, "Task " + task.getId() + " all-day mode: not scheduled for today, stopping if playing");
                AudioPlaybackService.stopTaskPlayback(context, task.getId());
            }
            
            // 调度下一次开始（用于重复任务）
            scheduleNextStartAllDay(task, startHour, startMinute, repeatDays);
            
            // 全天播放不设置结束闹钟
            cancelAlarm(task.getId(), false);
            return;
        }

        // 非全天播放模式：解析结束时间
        String[] endTimeParts = task.getEndTime().split(":");
        int endHour = Integer.parseInt(endTimeParts[0]);
        int endMinute = Integer.parseInt(endTimeParts[1]);
        
        // 计算今天的结束时间
        Calendar endCalendar = Calendar.getInstance();
        endCalendar.set(Calendar.HOUR_OF_DAY, endHour);
        endCalendar.set(Calendar.MINUTE, endMinute);
        endCalendar.set(Calendar.SECOND, 0);
        endCalendar.set(Calendar.MILLISECOND, 0);
        
        // 如果结束时间小于开始时间，说明跨天，结束时间加一天
        if (endCalendar.getTimeInMillis() <= startCalendar.getTimeInMillis()) {
            endCalendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        
        long todayEndTime = endCalendar.getTimeInMillis();
        
        // 检查当前是否在任务时间范围内
        boolean isWithinTimeRange = now >= todayStartTime && now < todayEndTime;
        
        Log.d(TAG, "Task " + task.getId() + " time check: now=" + new java.util.Date(now) 
                + ", startTime=" + new java.util.Date(todayStartTime) 
                + ", endTime=" + new java.util.Date(todayEndTime)
                + ", isWithinRange=" + isWithinTimeRange
                + ", shouldRunToday=" + shouldRunToday);
        
        if (shouldRunToday && isWithinTimeRange) {
            // 当前时间在任务时间范围内，立即开始播放
            Log.d(TAG, "Task " + task.getId() + " is within time range, starting immediately");
            AudioPlaybackService.startTaskPlayback(context, task.getId());
            
            // 设置结束闹钟
            Log.d(TAG, "Setting end alarm for task " + task.getId() + " at " + new java.util.Date(todayEndTime) + " (in " + ((todayEndTime - now) / 1000) + " seconds)");
            setAlarm(task.getId(), todayEndTime, false);
            
            // 调度下一次开始（明天或下一个重复日）
            scheduleNextStart(task, startHour, startMinute, endHour, endMinute, repeatDays);
        } else {
            // 当前时间不在范围内
            // 如果任务正在播放，需要停止它（用户可能修改了时间）
            Log.d(TAG, "Task " + task.getId() + " is NOT within time range, stopping if playing");
            AudioPlaybackService.stopTaskPlayback(context, task.getId());
            
            // 正常调度
            // 如果今天的开始时间已过，设置为明天
            if (todayStartTime <= now) {
                startCalendar.add(Calendar.DAY_OF_YEAR, 1);
            }
            
            // 检查重复日
            if (repeatDays != 0) {
                int maxDays = 7;
                while (maxDays > 0) {
                    int dayOfWeek = startCalendar.get(Calendar.DAY_OF_WEEK);
                    int dayFlag = getDayFlag(dayOfWeek);
                    if ((repeatDays & dayFlag) != 0) {
                        break;
                    }
                    startCalendar.add(Calendar.DAY_OF_YEAR, 1);
                    maxDays--;
                }
            }
            
            // 设置开始闹钟
            long startTime = startCalendar.getTimeInMillis();
            setAlarm(task.getId(), startTime, true);
            
            // 计算对应的结束时间
            Calendar scheduledEndCalendar = (Calendar) startCalendar.clone();
            scheduledEndCalendar.set(Calendar.HOUR_OF_DAY, endHour);
            scheduledEndCalendar.set(Calendar.MINUTE, endMinute);
            
            // 如果结束时间小于开始时间，说明跨天
            if (scheduledEndCalendar.getTimeInMillis() <= startTime) {
                scheduledEndCalendar.add(Calendar.DAY_OF_YEAR, 1);
            }
            
            long endTime = scheduledEndCalendar.getTimeInMillis();
            setAlarm(task.getId(), endTime, false);
            
            Log.d(TAG, "Scheduled task " + task.getId() + " start at " + startCalendar.getTime() + ", end at " + scheduledEndCalendar.getTime());
        }
    }
    
    /**
     * 调度下一次开始（全天播放模式）
     */
    private void scheduleNextStartAllDay(TaskEntity task, int startHour, int startMinute, int repeatDays) {
        if (repeatDays == 0) {
            // 非重复任务，不需要调度下一次
            return;
        }
        
        Calendar nextStartCalendar = Calendar.getInstance();
        nextStartCalendar.add(Calendar.DAY_OF_YEAR, 1);
        nextStartCalendar.set(Calendar.HOUR_OF_DAY, startHour);
        nextStartCalendar.set(Calendar.MINUTE, startMinute);
        nextStartCalendar.set(Calendar.SECOND, 0);
        nextStartCalendar.set(Calendar.MILLISECOND, 0);
        
        // 找到下一个重复日
        int maxDays = 7;
        while (maxDays > 0) {
            int dayOfWeek = nextStartCalendar.get(Calendar.DAY_OF_WEEK);
            int dayFlag = getDayFlag(dayOfWeek);
            if ((repeatDays & dayFlag) != 0) {
                break;
            }
            nextStartCalendar.add(Calendar.DAY_OF_YEAR, 1);
            maxDays--;
        }
        
        setAlarm(task.getId(), nextStartCalendar.getTimeInMillis(), true);
        Log.d(TAG, "Scheduled next start for all-day task " + task.getId() + " at " + nextStartCalendar.getTime());
    }
    
    /**
     * 调度下一次开始（用于已经在播放的任务）
     */
    private void scheduleNextStart(TaskEntity task, int startHour, int startMinute, int endHour, int endMinute, int repeatDays) {
        if (repeatDays == 0) {
            // 非重复任务，不需要调度下一次
            return;
        }
        
        Calendar nextStartCalendar = Calendar.getInstance();
        nextStartCalendar.add(Calendar.DAY_OF_YEAR, 1);
        nextStartCalendar.set(Calendar.HOUR_OF_DAY, startHour);
        nextStartCalendar.set(Calendar.MINUTE, startMinute);
        nextStartCalendar.set(Calendar.SECOND, 0);
        nextStartCalendar.set(Calendar.MILLISECOND, 0);
        
        // 找到下一个重复日
        int maxDays = 7;
        while (maxDays > 0) {
            int dayOfWeek = nextStartCalendar.get(Calendar.DAY_OF_WEEK);
            int dayFlag = getDayFlag(dayOfWeek);
            if ((repeatDays & dayFlag) != 0) {
                break;
            }
            nextStartCalendar.add(Calendar.DAY_OF_YEAR, 1);
            maxDays--;
        }
        
        setAlarm(task.getId(), nextStartCalendar.getTimeInMillis(), true);
        Log.d(TAG, "Scheduled next start for task " + task.getId() + " at " + nextStartCalendar.getTime());
    }

    /**
     * 取消任务调度
     */
    public void cancelTask(long taskId) {
        cancelAlarm(taskId, true);
        cancelAlarm(taskId, false);
        Log.d(TAG, "Cancelled task " + taskId);
    }

    /**
     * 重新调度所有启用的任务
     */
    public void rescheduleAllTasks() {
        executorService.execute(() -> {
            List<TaskEntity> tasks = AppDatabase.getInstance(context)
                    .taskDao()
                    .getEnabledTasksSync();
            for (TaskEntity task : tasks) {
                scheduleTask(task);
            }
            Log.d(TAG, "Rescheduled " + tasks.size() + " tasks");
        });
    }

    /**
     * 取消所有任务调度
     */
    public void cancelAllTasks() {
        executorService.execute(() -> {
            List<TaskEntity> tasks = AppDatabase.getInstance(context)
                    .taskDao()
                    .getAllTasksSync();
            for (TaskEntity task : tasks) {
                cancelTask(task.getId());
            }
        });
    }

    private void setAlarm(long taskId, long triggerTime, boolean isStart) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(isStart ? AlarmReceiver.ACTION_TASK_START : AlarmReceiver.ACTION_TASK_STOP);
        intent.putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId);

        int requestCode = isStart 
                ? REQUEST_CODE_START_PREFIX + (int) taskId 
                : REQUEST_CODE_STOP_PREFIX + (int) taskId;

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Log.d(TAG, "Setting alarm for task " + taskId + " (isStart=" + isStart + ") at " + new java.util.Date(triggerTime));

        // 使用 setAlarmClock - 这是最可靠的方式，系统会确保准时触发
        // 因为它被视为用户可见的闹钟，即使在 Doze 模式下也能准时触发
        if (isStart) {
            // 对于开始闹钟，使用 setAlarmClock 确保准时触发
            // 创建一个用于显示的 PendingIntent（点击闹钟通知时打开的界面）
            Intent showIntent = new Intent(context, com.caleb.scheduledplayer.presentation.ui.main.MainActivity.class);
            PendingIntent showPendingIntent = PendingIntent.getActivity(
                    context,
                    requestCode,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(
                    triggerTime,
                    showPendingIntent
            );
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent);
            Log.d(TAG, "Set AlarmClock for task " + taskId + " start at " + new java.util.Date(triggerTime));
        } else {
            // 对于结束闹钟，使用 setExactAndAllowWhileIdle
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                    );
                } else {
                    alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                    );
                    Log.w(TAG, "No exact alarm permission, using inexact alarm for stop");
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                );
            } else {
                alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                );
            }
            Log.d(TAG, "Set exact alarm for task " + taskId + " stop at " + new java.util.Date(triggerTime));
        }
    }

    private void cancelAlarm(long taskId, boolean isStart) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(isStart ? AlarmReceiver.ACTION_TASK_START : AlarmReceiver.ACTION_TASK_STOP);

        int requestCode = isStart 
                ? REQUEST_CODE_START_PREFIX + (int) taskId 
                : REQUEST_CODE_STOP_PREFIX + (int) taskId;

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.cancel(pendingIntent);
    }

    /**
     * 将 Calendar 的星期转换为 TaskEntity 的星期标志
     */
    private int getDayFlag(int calendarDayOfWeek) {
        switch (calendarDayOfWeek) {
            case Calendar.MONDAY:
                return TaskEntity.MONDAY;
            case Calendar.TUESDAY:
                return TaskEntity.TUESDAY;
            case Calendar.WEDNESDAY:
                return TaskEntity.WEDNESDAY;
            case Calendar.THURSDAY:
                return TaskEntity.THURSDAY;
            case Calendar.FRIDAY:
                return TaskEntity.FRIDAY;
            case Calendar.SATURDAY:
                return TaskEntity.SATURDAY;
            case Calendar.SUNDAY:
                return TaskEntity.SUNDAY;
            default:
                return 0;
        }
    }

    /**
     * 检查是否有精确闹钟权限
     */
    public boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return alarmManager.canScheduleExactAlarms();
        }
        return true;
    }
}
